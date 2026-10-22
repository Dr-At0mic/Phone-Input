package app.phoneinput.desktop.net.bluetooth

import app.phoneinput.desktop.Log
import app.phoneinput.protocol.PhoneInputProtocol
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.IOException

/**
 * RFCOMM through BlueZ kernel sockets (AF_BLUETOOTH). BlueZ only publishes SDP records over D-Bus,
 * so instead of a UUID the server takes the first free channel of
 * [PhoneInputProtocol.BLUETOOTH_CHANNELS] and the phone tries those channels in the same order.
 */
internal object LinuxRfcomm {
    private const val AF_BLUETOOTH = 31
    private const val SOCK_STREAM = 1
    private const val BTPROTO_RFCOMM = 3
    private const val SHUT_RDWR = 2

    /** sockaddr_rc: family u16 @0, bdaddr_t[6] @2 (BDADDR_ANY), channel u8 @8; padded to 10. */
    private const val SOCKADDR_RC_SIZE = 10

    private const val EADDRINUSE = 98
    private const val EAFNOSUPPORT = 97
    private const val EADDRNOTAVAIL = 99
    private const val ENODEV = 19
    private const val EPROTONOSUPPORT = 93

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun socket(domain: Int, type: Int, protocol: Int): Int
        fun bind(fd: Int, addr: Pointer, len: Int): Int
        fun listen(fd: Int, backlog: Int): Int
        fun accept(fd: Int, addr: Pointer?, len: Pointer?): Int
        fun read(fd: Int, buf: ByteArray, count: Long): Long
        fun write(fd: Int, buf: ByteArray, count: Long): Long
        fun shutdown(fd: Int, how: Int): Int
        fun close(fd: Int): Int
    }

    private val libc: LibC by lazy { Native.load("c", LibC::class.java) }

    fun listen(): RfcommServer {
        val c = libc
        var lastError = 0
        for (channel in PhoneInputProtocol.BLUETOOTH_CHANNELS) {
            val fd = c.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
            if (fd < 0) throw unavailable("create a Bluetooth socket", Native.getLastError())
            val addr = Memory(SOCKADDR_RC_SIZE.toLong()).apply {
                clear()
                setShort(0, AF_BLUETOOTH.toShort())
                setByte(8, channel.toByte())
            }
            if (c.bind(fd, addr, SOCKADDR_RC_SIZE) != 0) {
                lastError = Native.getLastError()
                c.close(fd)
                if (lastError == EADDRINUSE) continue
                throw unavailable("bind RFCOMM channel $channel", lastError)
            }
            if (c.listen(fd, 4) != 0) {
                val e = Native.getLastError()
                c.close(fd)
                throw unavailable("listen on Bluetooth", e)
            }
            Log.i("Bluetooth: listening on RFCOMM channel $channel")
            return Server(c, fd, channel)
        }
        throw IOException("RFCOMM channels ${PhoneInputProtocol.BLUETOOTH_CHANNELS} are all in use (errno $lastError).")
    }

    private fun unavailable(action: String, errno: Int): IOException = when (errno) {
        EAFNOSUPPORT, EPROTONOSUPPORT, EADDRNOTAVAIL, ENODEV ->
            BluetoothUnavailableException("Bluetooth is off or this computer has no Bluetooth adapter.")
        else -> IOException("Couldn't $action (errno $errno).")
    }

    private class Server(private val c: LibC, private val fd: Int, override val channel: Int) : RfcommServer {
        @Volatile private var closed = false

        override fun accept(): RfcommConnection {
            val client = c.accept(fd, null, null)
            if (client < 0) {
                val e = Native.getLastError()
                throw IOException(if (closed) "Bluetooth server closed" else "Bluetooth accept failed (errno $e)")
            }
            return RfcommConnection(
                input = NativeInputStream { buf, len ->
                    val n = c.read(client, buf, len.toLong())
                    if (n < 0) throw IOException("Bluetooth receive failed (errno ${Native.getLastError()})")
                    n.toInt()
                },
                output = NativeOutputStream { buf, len ->
                    val n = c.write(client, buf, len.toLong())
                    if (n < 0) throw IOException("Bluetooth send failed (errno ${Native.getLastError()})")
                    n.toInt()
                },
                onClose = {
                    c.shutdown(client, SHUT_RDWR)
                    c.close(client)
                },
            )
        }

        override fun close() {
            if (closed) return
            closed = true
            c.shutdown(fd, SHUT_RDWR) // wakes a blocked accept(); close() alone doesn't on Linux
            c.close(fd)
        }
    }
}
