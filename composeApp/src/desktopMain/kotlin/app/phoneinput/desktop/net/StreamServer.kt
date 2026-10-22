package app.phoneinput.desktop.net

import app.phoneinput.audio.AudioLevels
import app.phoneinput.audio.JitterBuffer
import app.phoneinput.desktop.Log
import app.phoneinput.protocol.Codec
import app.phoneinput.protocol.Packet
import app.phoneinput.protocol.PacketCodec
import app.phoneinput.protocol.PhoneInputProtocol
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.io.DataInputStream
import java.io.EOFException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Receives audio from any number of phones, over Wi-Fi (UDP), USB (TCP via `adb reverse`) or
 * Bluetooth (RFCOMM, see [BluetoothBridge]); exactly one of them (the active device) feeds the
 * [JitterBuffer] and therefore the virtual microphone. The others stay connected on standby so
 * switching is instant. Each phone learns its role through [Packet.State].
 *
 * A session ends on Bye or after [PhoneInputProtocol.SESSION_TIMEOUT_MS] of silence. Pings are
 * answered only for known sessions, so a phone that lost its session says Hello again.
 */
class StreamServer(
    private val listenPort: Int = PhoneInputProtocol.AUDIO_PORT,
    /** Loopback TCP port for USB phones; null disables USB. */
    private val usbPort: Int? = PhoneInputProtocol.USB_PORT,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    data class Device(
        val sessionId: Int,
        val name: String,
        val address: String,
        val transport: Transport,
        val codec: Codec,
        /** This phone is playing the computer's audio. */
        val speaker: Boolean = false,
        val connected: Boolean,
        val active: Boolean,
        val connectedAtMs: Long,
        val lastSeenMs: Long,
    )

    data class Snapshot(val devices: List<Device> = emptyList()) {
        val active: Device? get() = devices.firstOrNull { it.active }
        val connected: List<Device> get() = devices.filter { it.connected }
    }

    private class Session(
        val id: Int,
        val name: String,
        val peer: Peer,
        val codec: Codec,
        val startedAtMs: Long,
        @Volatile var lastSeenMs: Long,
        @Volatile var levelDb: Float = AudioLevels.FLOOR_DB,
        @Volatile var speaker: Boolean = false,
    ) {
        /** Packets of one session arrive on one thread (the UDP loop or its stream), in order. */
        val decoder: OpusDecoder? = if (codec == Codec.Opus) OpusDecoder(PhoneInputProtocol.SAMPLE_RATE, PhoneInputProtocol.CHANNELS) else null
        var lastOpusSeq: Int? = null

        /** Identifies the same device across reconnects (for the remembered choice and "Recent"). */
        val key: String get() = "${peer.transport}:${if (peer is UdpPeer) peer.label else name}"
    }

    private val jitter = JitterBuffer()
    private val lock = Any()
    /** Read lock-free by the network and audio threads; structural changes happen under [lock]. */
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val offline = LinkedHashMap<String, Device>()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile
    private var activeId: Int? = null

    /** Told how many phones want the computer's audio, whenever that changes (see [SpeakerStreamer]). */
    @Volatile
    var onSpeakerListeners: ((Int) -> Unit)? = null
    private var speakerListeners = 0

    /** The device the user picked last; it becomes active again whenever it reconnects. */
    @Volatile
    private var preferredKey: String? = null
    private var socket: DatagramSocket? = null
    private var usbServer: ServerSocket? = null

    val boundPort: Int get() = socket?.localPort ?: -1
    val usbBoundPort: Int get() = usbServer?.localPort ?: -1

    val hasActiveSession: Boolean get() = activeId != null

    /**
     * Binds the audio port. Not SO_REUSEADDR: on Windows that lets a second PhoneInput bind the same
     * port and the OS then hands each datagram to only one of them. Throws BindException if taken.
     */
    fun bind() {
        socket = DatagramSocket(null).apply {
            reuseAddress = false
            bind(InetSocketAddress(listenPort))
        }
        Log.i("Audio server bound to UDP $boundPort")
        if (usbPort != null) {
            // Loopback only: USB phones arrive through adb reverse, never from the network.
            usbServer = ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), usbPort))
            }
            Log.i("USB server bound to TCP 127.0.0.1:$usbBoundPort")
        }
    }

    fun start() {
        if (socket == null) bind()
        val s = socket!!
        thread(name = "pm-stream-server", isDaemon = true) { receiveLoop(s) }
        usbServer?.let { server -> thread(name = "pm-usb-accept", isDaemon = true) { acceptLoop(server) } }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            client.tcpNoDelay = true
            serve(TcpPeer(client))
        }
    }

    /** Serves one stream connection (USB or Bluetooth) on its own thread until it closes. */
    fun serve(peer: StreamPeer) {
        thread(name = "pm-${peer.transport.name.lowercase()}-peer", isDaemon = true) { streamLoop(peer) }
    }

    /** Reads framed packets from one stream connection; the session ends when the cable/link closes. */
    private fun streamLoop(peer: StreamPeer) {
        val kind = peer.transport.label
        Log.i("$kind connection opened")
        val input = DataInputStream(peer.input.buffered())
        val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)
        try {
            while (true) {
                val length = input.readUnsignedShort()
                if (length > buf.size) throw IOException("Oversized frame ($length bytes)")
                input.readFully(buf, 0, length)
                val packet = PacketCodec.decode(buf, length) ?: continue
                try {
                    handle(packet, peer)
                } catch (e: RuntimeException) {
                    Log.e("Bad $kind packet handling; keep serving", e)
                }
            }
        } catch (_: EOFException) {
        } catch (e: IOException) {
            if (!peer.closed) Log.w("$kind connection error: ${e.message}")
        } finally {
            runCatching { peer.close() }
            synchronized(lock) { sessions.values.filter { it.peer === peer }.forEach { remove(it, "disconnected ($kind closed)") } }
            Log.i("$kind connection closed")
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)
        val dp = DatagramPacket(buf, buf.size)
        while (!s.isClosed) {
            try {
                dp.length = buf.size
                s.receive(dp)
                val packet = PacketCodec.decode(buf, dp.length) ?: continue
                handle(packet, UdpPeer(dp.socketAddress as InetSocketAddress, s))
            } catch (e: SocketException) {
                if (s.isClosed) break
            } catch (e: IOException) {
                Log.w("Audio receive error", e) // transient; keep serving
            } catch (e: RuntimeException) {
                Log.e("Bad packet handling; keep serving", e)
            }
        }
        Log.i("Audio server stopped")
    }

    internal fun handle(packet: Packet, from: Peer) {
        val now = clock()
        when (packet) {
            is Packet.Hello -> onHello(packet, from, now)
            is Packet.Audio -> {
                val s = sessions[packet.sessionId] ?: return
                onAudio(s, packet.seq, packet.samples, now)
            }
            is Packet.OpusAudio -> {
                val s = sessions[packet.sessionId] ?: return
                decodeOpus(s, packet)
                s.lastSeenMs = now
            }
            is Packet.Ping -> {
                val s = sessions[packet.sessionId] ?: return
                s.lastSeenMs = now
                send(Packet.Pong(packet.sessionId, packet.timestampMs), from)
                send(Packet.State(packet.sessionId, packet.sessionId == activeId), from)
            }
            is Packet.SpeakerRequest -> {
                val s = sessions[packet.sessionId] ?: return
                s.lastSeenMs = now
                synchronized(lock) {
                    if (s.speaker != packet.on && sessions[s.id] === s) {
                        s.speaker = packet.on
                        Log.i("${s.name} ${if (packet.on) "started" else "stopped"} playing computer audio")
                        publish()
                        updateSpeakerListeners()
                    }
                }
                send(Packet.SpeakerState(s.id, s.speaker, ""), from)
            }
            is Packet.Bye -> synchronized(lock) {
                sessions[packet.sessionId]?.let { remove(it, "said goodbye") }
            }
            else -> Unit
        }
    }

    private fun onAudio(s: Session, seq: Int, samples: ShortArray, now: Long) {
        s.lastSeenMs = now
        s.levelDb = AudioLevels.rmsDbfs(samples)
        if (s.id == activeId) jitter.put(seq, samples)
    }

    /**
     * Decodes in arrival order. A short gap (lost UDP datagrams, or frames the phone dropped while
     * the link stalled) is filled with Opus's own concealment, which keeps the decoder in step and
     * sounds far better than silence; if the real frame still turns up in time it replaces it.
     */
    private fun decodeOpus(s: Session, packet: Packet.OpusAudio) {
        val decoder = s.decoder ?: return
        val now = clock()
        try {
            val last = s.lastOpusSeq
            if (last != null && packet.seq <= last) return // duplicate or too late to keep the decoder in order
            if (last != null) {
                val missing = packet.seq - last - 1
                if (missing in 1..MAX_CONCEALED_FRAMES) {
                    for (seq in last + 1 until packet.seq) {
                        val pcm = ShortArray(PhoneInputProtocol.FRAME_SAMPLES)
                        decoder.decode(null, 0, 0, pcm, 0, pcm.size, false)
                        onAudio(s, seq, pcm, now)
                    }
                }
            }
            s.lastOpusSeq = packet.seq
            val pcm = ShortArray(PhoneInputProtocol.FRAME_SAMPLES)
            val n = decoder.decode(packet.data, 0, packet.data.size, pcm, 0, pcm.size, false)
            onAudio(s, packet.seq, if (n == pcm.size) pcm else pcm.copyOf(n.coerceAtLeast(0)), now)
        } catch (e: OpusException) {
            Log.w("Opus decode failed for ${s.name}: ${e.message}")
        }
    }

    private fun onHello(hello: Packet.Hello, from: Peer, now: Long) {
        val supported = hello.sampleRate == PhoneInputProtocol.SAMPLE_RATE && hello.channels == PhoneInputProtocol.CHANNELS
        if (!supported) {
            Log.w("Rejected ${hello.deviceName} at $from: unsupported format ${hello.sampleRate} Hz x${hello.channels}")
            send(Packet.HelloAck(hello.sessionId, false), from)
            return
        }
        var codec = if (hello.codecs and Codec.Opus.bit != 0) Codec.Opus else Codec.Pcm
        val accepted = synchronized(lock) {
            val existing = sessions[hello.sessionId]
            if (existing != null) {
                existing.lastSeenMs = now
                codec = existing.codec
                true
            } else if (sessions.size >= MAX_DEVICES) {
                Log.w("Rejected ${hello.deviceName} at $from: $MAX_DEVICES devices already connected")
                false
            } else {
                // A phone that restarted streaming comes back with a new session id.
                sessions.values.filter { it.peer == from }.forEach { remove(it, "reconnected", notify = false) }
                val session = Session(hello.sessionId, hello.deviceName.ifBlank { "Phone" }, from, codec, now, now)
                sessions[session.id] = session
                offline.remove(session.key)
                Log.i("Device connected: ${session.name} via ${from.transport.label}${if (from is UdpPeer) " " + from.label else ""}, ${codec.label} (session ${session.id})")
                if (activeId == null || session.key == preferredKey) makeActive(session, announce = false) // its State follows the HelloAck
                publish()
                true
            }
        }
        send(Packet.HelloAck(hello.sessionId, accepted, codec), from)
        if (accepted) send(Packet.State(hello.sessionId, hello.sessionId == activeId), from)
    }

    /** Sends one encoded frame of the computer's audio to every phone that asked for it. */
    fun sendSpeaker(seq: Int, data: ByteArray) {
        for (s in sessions.values) if (s.speaker) send(Packet.SpeakerAudio(s.id, seq, data), s.peer)
    }

    /** Capture failed: turn speaker mode off for everyone and tell the phones why. */
    fun speakerFailed(message: String) {
        synchronized(lock) {
            for (s in sessions.values.filter { it.speaker }) {
                s.speaker = false
                send(Packet.SpeakerState(s.id, false, message), s.peer)
            }
            publish()
            updateSpeakerListeners()
        }
    }

    private fun updateSpeakerListeners() {
        val count = sessions.values.count { it.speaker }
        if (count != speakerListeners) {
            speakerListeners = count
            onSpeakerListeners?.invoke(count)
        }
    }

    /** Makes [sessionId] the device that feeds the virtual microphone. */
    fun select(sessionId: Int) {
        synchronized(lock) {
            val s = sessions[sessionId] ?: return
            preferredKey = s.key
            if (activeId != sessionId) makeActive(s)
            publish()
        }
    }

    /** Called by the audio thread every frame. */
    fun takeFrame(): ShortArray? = if (activeId != null) jitter.take() else null

    fun checkTimeouts() {
        val now = clock()
        if (sessions.values.none { now - it.lastSeenMs > PhoneInputProtocol.SESSION_TIMEOUT_MS }) return
        synchronized(lock) {
            sessions.values.filter { now - it.lastSeenMs > PhoneInputProtocol.SESSION_TIMEOUT_MS }
                .forEach { remove(it, "timed out") }
        }
    }

    fun disconnect(sessionId: Int) {
        synchronized(lock) {
            val s = sessions[sessionId] ?: return
            send(Packet.Bye(s.id), s.peer)
            remove(s, "disconnected from this computer")
        }
    }

    fun disconnectActive() {
        activeId?.let(::disconnect)
    }

    /** Removes one disconnected device from the "Recent" list. */
    fun forgetRecent(sessionId: Int) {
        synchronized(lock) {
            if (offline.entries.removeIf { it.value.sessionId == sessionId }) publish()
        }
    }

    /** Empties the "Recent" list; connected devices stay. */
    fun clearRecent() {
        synchronized(lock) {
            if (offline.isEmpty()) return
            offline.clear()
            publish()
        }
    }

    /** Latest input level (dBFS) per connected session, for the device list meters. */
    fun levels(): Map<Int, Float> = sessions.values.associate { it.id to it.levelDb }

    fun bufferedMs(): Int = jitter.depthFrames() * PhoneInputProtocol.FRAME_MS

    /** How much the jitter buffer currently aims to hold, adapted to the active link. */
    fun targetBufferMs(): Int = jitter.targetDepthFrames() * PhoneInputProtocol.FRAME_MS

    fun stats(): JitterBuffer.Stats = jitter.stats()

    private fun makeActive(s: Session, announce: Boolean = true) {
        val previous = activeId?.let { sessions[it] }
        activeId = s.id
        jitter.reset()
        Log.i("Active device: ${s.name}")
        previous?.let { send(Packet.State(it.id, false), it.peer) }
        if (announce) send(Packet.State(s.id, true), s.peer)
    }

    private fun remove(s: Session, reason: String, notify: Boolean = true) {
        sessions.remove(s.id)
        Log.i("Device ${s.name} $reason")
        offline.remove(s.key)
        offline[s.key] = Device(s.id, s.name, s.peer.label, s.peer.transport, s.codec, connected = false, active = false, connectedAtMs = s.startedAtMs, lastSeenMs = s.lastSeenMs)
        while (offline.size > MAX_OFFLINE) offline.remove(offline.keys.first())
        if (activeId == s.id) {
            activeId = null
            jitter.reset()
            // Prefer the phone the user picked, else the most recently connected one.
            val next = sessions.values.firstOrNull { it.key == preferredKey } ?: sessions.values.maxByOrNull { it.startedAtMs }
            if (next != null) makeActive(next)
        }
        if (notify) publish()
        if (s.speaker) updateSpeakerListeners()
    }

    private fun publish() {
        val active = activeId
        val connected = sessions.values.sortedBy { it.startedAtMs }.map {
            Device(it.id, it.name, it.peer.label, it.peer.transport, it.codec, speaker = it.speaker, connected = true, active = it.id == active, connectedAtMs = it.startedAtMs, lastSeenMs = it.lastSeenMs)
        }
        _snapshot.value = Snapshot(connected + offline.values.toList().asReversed())
    }

    private fun send(packet: Packet, to: Peer) = to.send(PacketCodec.encode(packet))

    override fun close() {
        synchronized(lock) { sessions.keys.toList() }.forEach(::disconnect)
        socket?.close()
        usbServer?.close()
    }

    private companion object {
        const val MAX_DEVICES = 8
        const val MAX_OFFLINE = 6

        /** Longer gaps are left to the jitter buffer's fade-out instead of synthesizing audio. */
        const val MAX_CONCEALED_FRAMES = 5
    }
}
