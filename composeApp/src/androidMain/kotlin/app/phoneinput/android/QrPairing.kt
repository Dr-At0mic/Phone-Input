package app.phoneinput.android

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.phoneinput.protocol.Packet
import app.phoneinput.protocol.PacketCodec
import app.phoneinput.protocol.PairingInfo
import app.phoneinput.protocol.PhoneInputProtocol
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * QR pairing: the desktop shows a `phoneinput://connect?…` code; scanning it connects at once.
 *
 * Uses Google's code scanner (Play services), which shows its own camera screen, so PhoneInput
 * needs no camera permission.
 */
object QrPairing {
    private const val TAG = "PhoneInput"

    fun scan(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val raw = code.rawValue
                if (raw == null) onError("That QR code is empty.") else onResult(raw)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "QR scanner failed", e)
                onError("Couldn't open the QR scanner (${e.message ?: "Google Play services needed"}). Add the computer by its address instead.")
            }
    }

    /**
     * Picks the address from the code that this phone can actually reach: probes each with a
     * Discover packet and takes the first Announce. Falls back to the first address.
     */
    suspend fun resolve(info: PairingInfo): Host = withContext(Dispatchers.IO) {
        val fallback = Host(info.name, info.addresses.first(), info.port, info.os)
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 250
                val nonce = Random.nextInt()
                val probe = PacketCodec.encode(Packet.Discover(nonce))
                val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)
                val deadline = SystemClock.elapsedRealtime() + 1_500
                var lastProbe = 0L
                while (SystemClock.elapsedRealtime() < deadline) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProbe >= 500) {
                        lastProbe = now
                        info.addresses.forEach { a ->
                            runCatching {
                                sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName(a), PhoneInputProtocol.DISCOVERY_PORT))
                            }
                        }
                    }
                    val dp = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(dp)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val reply = PacketCodec.decode(buf, dp.length)
                    val from = dp.address?.hostAddress
                    if (reply is Packet.Announce && reply.nonce == nonce && from != null) {
                        Log.i(TAG, "QR: ${info.name} answered at $from")
                        return@withContext Host(info.name, from, reply.audioPort, reply.os)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "QR probe failed", e)
        }
        Log.i(TAG, "QR: no answer, trying ${fallback.address}")
        fallback
    }
}
