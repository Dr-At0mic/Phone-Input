package app.phoneinput.android.stream

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import app.phoneinput.protocol.Packet
import app.phoneinput.protocol.PacketCodec
import app.phoneinput.protocol.PhoneInputProtocol
import app.phoneinput.protocol.StreamFraming
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One connection to the computer. [receive] blocks until a packet arrives; it throws once closed. */
interface Link : Closeable {
    fun send(packet: Packet)

    /** Next decodable packet; null for datagrams that aren't PhoneInput packets. */
    fun receive(): Packet?
}

/** Wi-Fi: plain UDP datagrams. */
class UdpLink(address: String, port: Int) : Link {
    private val target = InetSocketAddress(InetAddress.getByName(address), port)
    private val socket = DatagramSocket()
    private val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)

    override fun send(packet: Packet) {
        val bytes = PacketCodec.encode(packet)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, target))
        } catch (_: IOException) {
            // Network blip (e.g. Wi-Fi roaming); the engine's health check handles prolonged loss.
        }
    }

    override fun receive(): Packet? {
        val dp = DatagramPacket(buf, buf.size)
        socket.receive(dp)
        return PacketCodec.decode(buf, dp.length)
    }

    override fun close() = socket.close()

    override fun toString() = "UDP $target"
}

/**
 * A byte stream (USB/TCP or Bluetooth RFCOMM) carrying length-framed packets. Any I/O error means
 * the link is gone.
 *
 * [send] never blocks: packets go through a bounded queue to a writer thread. Stream links stall
 * now and then (Bluetooth retransmits, adb tunnels), and a blocking write on the capture thread
 * would overflow the microphone buffer and lose audio before it left the phone. After a stall the
 * writer sends the backlog in one batch; if the backlog outgrows [QUEUE_PACKETS] the oldest
 * packets are dropped so delay can't pile up.
 */
abstract class FramedLink(inputStream: InputStream, outputStream: OutputStream) : Link {
    private val input = DataInputStream(inputStream.buffered())
    private val output = DataOutputStream(outputStream.buffered(16 * 1024))
    private val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_PACKETS)

    @Volatile
    var broken = false
        protected set

    @Volatile
    var droppedPackets = 0
        private set

    init {
        thread(name = "pm-link-writer", isDaemon = true, priority = Thread.MAX_PRIORITY) { writeLoop() }
    }

    override fun send(packet: Packet) {
        if (broken) return
        val frame = StreamFraming.frame(PacketCodec.encode(packet))
        while (!queue.offer(frame)) {
            if (queue.poll() != null) droppedPackets++
        }
    }

    /** Gives queued packets (e.g. the final Bye) a moment to go out before the socket closes. */
    protected fun drain() {
        val deadline = System.currentTimeMillis() + 300
        while (queue.isNotEmpty() && !broken && System.currentTimeMillis() < deadline) Thread.sleep(5)
        if (!broken) Thread.sleep(20) // the writer flushes after taking the last batch
    }

    private fun writeLoop() {
        try {
            while (!broken) {
                val first = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                output.write(first)
                while (true) output.write(queue.poll() ?: break)
                output.flush()
            }
        } catch (_: IOException) {
            broken = true
        } catch (_: InterruptedException) {
        }
    }

    override fun receive(): Packet? {
        try {
            val length = input.readUnsignedShort()
            if (length > buf.size) throw IOException("Oversized frame")
            input.readFully(buf, 0, length)
            return PacketCodec.decode(buf, length)
        } catch (e: IOException) {
            broken = true
            throw e
        }
    }
}

/**
 * USB: TCP to 127.0.0.1:5825, which `adb reverse` (set up by the desktop app) carries over the
 * cable to the computer.
 */
class TcpLink private constructor(private val socket: Socket) : FramedLink(socket.getInputStream(), socket.getOutputStream()) {
    constructor(address: String, port: Int) : this(
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(address, port), 1_500)
        },
    )

    override fun close() {
        drain()
        broken = true
        socket.close()
    }

    override fun toString() = "USB (TCP ${socket.remoteSocketAddress})"
}

/**
 * Bluetooth: RFCOMM to a computer paired with this phone. Windows advertises PhoneInput's service
 * UUID over SDP; on Linux there's no SDP record, so the fixed channels are tried in order. The
 * way that worked is remembered per computer and tried first next time.
 */
class BluetoothLink private constructor(private val socket: BluetoothSocket, private val via: String) :
    FramedLink(socket.inputStream, socket.outputStream) {

    override fun close() {
        drain()
        broken = true
        socket.close()
    }

    override fun toString() = "Bluetooth ${socket.remoteDevice.address} ($via)"

    companion object {
        private const val TAG = "PhoneInput"
        private val serviceUuid = UUID.fromString(PhoneInputProtocol.BLUETOOTH_SERVICE_UUID)

        /** Bluetooth address → what connected last time: 0 for the SDP service, else the channel. */
        private val known = ConcurrentHashMap<String, Int>()

        /** Connects (blocking, several seconds at worst); throws IOException with a user-facing message. */
        @SuppressLint("MissingPermission") // The UI asks for BLUETOOTH_CONNECT before offering Bluetooth hosts.
        fun open(context: Context, address: String): BluetoothLink {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("This phone has no Bluetooth.")
            if (!adapter.isEnabled) throw IOException("Bluetooth is off. Turn it on and try again.")
            val device = adapter.getRemoteDevice(address)
            val attempts = (listOf(0) + PhoneInputProtocol.BLUETOOTH_CHANNELS).sortedBy { if (it == known[address]) 0 else 1 }
            var last: Exception? = null
            for (channel in attempts) {
                val socket = try {
                    if (channel == 0) device.createRfcommSocketToServiceRecord(serviceUuid) else rfcommOnChannel(device, channel)
                } catch (e: Exception) {
                    last = e
                    continue
                }
                try {
                    socket.connect()
                    known[address] = channel
                    return BluetoothLink(socket, if (channel == 0) "service" else "channel $channel")
                } catch (e: IOException) {
                    Log.d(TAG, "Bluetooth ${if (channel == 0) "service" else "channel $channel"} on $address: ${e.message}")
                    runCatching { socket.close() }
                    last = e
                }
            }
            Log.d(TAG, "Bluetooth link to $address failed: ${last?.message}")
            throw IOException("the computer didn't answer", last)
        }

        /** Hidden API, but the only way to reach a channel without an SDP record (Linux computers). */
        private fun rfcommOnChannel(device: BluetoothDevice, channel: Int): BluetoothSocket =
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, channel) as BluetoothSocket
    }
}

/** About 300 ms of 10 ms audio frames (plus the odd Ping). */
private const val QUEUE_PACKETS = 32
