package app.phoneinput.desktop.net

import app.phoneinput.protocol.StreamFraming
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

enum class Transport(val label: String) { WiFi("Wi-Fi"), Usb("USB"), Bluetooth("Bluetooth") }

/** Where a session's packets come from and replies go to. */
sealed interface Peer {
    val transport: Transport

    /** Shown in the UI: an IP address, "USB" or "Bluetooth". */
    val label: String

    fun send(bytes: ByteArray)
}

/** A phone on the network, identified by its UDP source address. */
data class UdpPeer(val address: InetSocketAddress, private val socket: DatagramSocket) : Peer {
    override val transport get() = Transport.WiFi
    override val label: String get() = address.address.hostAddress

    override fun send(bytes: ByteArray) {
        try {
            socket.send(DatagramPacket(bytes, bytes.size, address))
        } catch (_: IOException) {
            // Best effort: the phone retries Hello and Ping on its own.
        }
    }
}

/**
 * A phone on a byte stream (USB cable through `adb reverse`, or Bluetooth RFCOMM): one connection,
 * length-framed packets. The session ends when the stream closes.
 *
 * [send] never blocks: a writer thread drains a bounded queue, so a stalled Bluetooth link can't
 * hold up the threads that serve other phones (or the computer-audio pacer). If the queue fills,
 * the oldest packets are dropped.
 */
open class StreamPeer(
    override val transport: Transport,
    val input: InputStream,
    output: OutputStream,
    private val connection: Closeable,
) : Peer, Closeable {
    private val out = DataOutputStream(output.buffered(16 * 1024))
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_PACKETS)
    override val label: String get() = transport.label

    @Volatile
    var closed = false
        private set

    init {
        thread(name = "pm-${transport.name.lowercase()}-writer", isDaemon = true) { writeLoop() }
    }

    override fun send(bytes: ByteArray) {
        if (closed) return
        val frame = StreamFraming.frame(bytes)
        while (!queue.offer(frame)) queue.poll()
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val first = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(first)
                while (true) out.write(queue.poll() ?: break)
                out.flush()
            }
        } catch (_: IOException) {
            // The reader thread notices the closed connection and ends the session.
        } catch (_: InterruptedException) {
        }
    }

    override fun close() {
        closed = true
        connection.close()
    }
}

/** A phone on a USB cable: TCP through `adb reverse`. */
class TcpPeer(val socket: Socket) : StreamPeer(Transport.Usb, socket.getInputStream(), socket.getOutputStream(), socket)

/** About 300 ms of computer audio plus control packets. */
private const val QUEUE_PACKETS = 48
