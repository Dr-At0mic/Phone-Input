package app.phoneinput.android.stream

import android.os.SystemClock
import app.phoneinput.android.Host
import app.phoneinput.protocol.Packet
import app.phoneinput.protocol.PacketCodec
import app.phoneinput.protocol.PhoneInputProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Finds PhoneInput desktops on the local network: broadcasts a Discover probe every 1.5 s and
 * collects the unicast Announce replies. Hosts not heard from for 6 s drop off the list.
 */
class DiscoveryClient {
    private val _hosts = MutableStateFlow<List<Host>>(emptyList())
    val hosts: StateFlow<List<Host>> = _hosts.asStateFlow()

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "pm-discovery", isDaemon = true) { run() }
    }

    fun stop() {
        running = false
        socket?.close()
    }

    private fun run() {
        val seen = LinkedHashMap<String, Pair<Host, Long>>()
        val nonce = Random.nextInt()
        val probe = PacketCodec.encode(Packet.Discover(nonce))
        val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)
        try {
            DatagramSocket().use { sock ->
                socket = sock
                sock.broadcast = true
                sock.soTimeout = 250
                val broadcast = InetAddress.getByName("255.255.255.255")
                var lastProbe = 0L
                while (running) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProbe >= 1_500) {
                        lastProbe = now
                        try {
                            sock.send(DatagramPacket(probe, probe.size, broadcast, PhoneInputProtocol.DISCOVERY_PORT))
                        } catch (_: IOException) {
                            // No network right now; keep trying.
                        }
                    }
                    val dp = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(dp)
                        val reply = PacketCodec.decode(buf, dp.length)
                        val address = dp.address?.hostAddress
                        if (reply is Packet.Announce && reply.nonce == nonce && address != null) {
                            seen[address] = Host(reply.name, address, reply.audioPort, reply.os) to now
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                    seen.entries.removeAll { now - it.value.second > 6_000 }
                    val list = seen.values.map { it.first }
                    if (list != _hosts.value) _hosts.value = list
                }
            }
        } catch (_: IOException) {
            // Socket closed by stop().
        } finally {
            running = false
        }
    }
}
