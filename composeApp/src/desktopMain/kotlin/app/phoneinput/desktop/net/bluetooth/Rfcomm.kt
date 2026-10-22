package app.phoneinput.desktop.net.bluetooth

import app.phoneinput.protocol.HostOs
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A listening Bluetooth RFCOMM socket. [accept] blocks; [close] from another thread unblocks it. */
interface RfcommServer : Closeable {
    val channel: Int

    fun accept(): RfcommConnection
}

/** One accepted RFCOMM connection: a plain byte stream. */
class RfcommConnection(
    val input: InputStream,
    val output: OutputStream,
    private val onClose: () -> Unit,
) : Closeable {
    @Volatile
    var closed = false
        private set

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}

/** Thrown when this computer can't do Bluetooth right now; the message is shown in the UI. */
class BluetoothUnavailableException(message: String) : IOException(message)

object Rfcomm {
    fun supported(os: HostOs): Boolean = os == HostOs.Windows || os == HostOs.Linux

    /** Opens an RFCOMM server for PhoneInput (advertised over SDP where the OS allows it). */
    fun listen(os: HostOs, serviceName: String): RfcommServer = when (os) {
        HostOs.Windows -> WindowsRfcomm.listen(serviceName)
        HostOs.Linux -> LinuxRfcomm.listen()
        else -> throw BluetoothUnavailableException("Bluetooth isn't supported on this system.")
    }
}

/** Adapts a native `recv`-style call to an InputStream; [recv] returns bytes read, 0 at EOF, or throws. */
internal class NativeInputStream(private val recv: (ByteArray, Int) -> Int) : InputStream() {
    private val buf = ByteArray(4096)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = recv(buf, minOf(len, buf.size))
        if (n <= 0) return -1
        System.arraycopy(buf, 0, b, off, n)
        return n
    }
}

/** Adapts a native `send`-style call to an OutputStream; [send] returns bytes written or throws. */
internal class NativeOutputStream(private val send: (ByteArray, Int) -> Int) : OutputStream() {
    private val buf = ByteArray(4096)

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val chunk = minOf(len - done, buf.size)
            System.arraycopy(b, off + done, buf, 0, chunk)
            var sent = 0
            while (sent < chunk) {
                val n = if (sent == 0) send(buf, chunk) else send(buf.copyOfRange(sent, chunk), chunk - sent)
                if (n <= 0) throw IOException("Bluetooth connection closed")
                sent += n
            }
            done += chunk
        }
    }
}
