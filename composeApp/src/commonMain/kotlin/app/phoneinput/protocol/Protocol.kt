package app.phoneinput.protocol

/**
 * PhoneInput wire protocol (UDP, big-endian header, little-endian PCM).
 *
 * Every datagram starts with a 4-byte header: 'P' 'I' <version> <type>.
 * The phone discovers computers by broadcasting [Packet.Discover] to [DISCOVERY_PORT];
 * each desktop answers with [Packet.Announce]. Streaming happens on [AUDIO_PORT]:
 * Hello → HelloAck, then a stream of Audio frames plus Ping/Pong once per second.
 * Several phones may be connected; the desktop plays one (the active one) and tells each
 * phone its role with [Packet.State], sent on every change and after each Pong.
 */
object PhoneInputProtocol {
    const val VERSION = 2
    const val AUDIO_PORT = 5823
    const val DISCOVERY_PORT = 5824

    /**
     * USB: TCP on the computer's loopback, reached from the phone through `adb reverse`
     * (adb only forwards TCP). Packets are framed as <u16 length><packet>.
     */
    const val USB_PORT = 5825

    /**
     * Bluetooth: an RFCOMM stream from the phone to the (already paired) computer, framed like USB.
     * Windows advertises this service UUID over SDP. BlueZ only allows SDP records through D-Bus,
     * so on Linux the desktop listens on the first free channel of [BLUETOOTH_CHANNELS] and the
     * phone tries them in order.
     */
    const val BLUETOOTH_SERVICE_UUID = "5f1d6c2a-8b3e-4a7d-9c55-2e7b1f0a9d43"
    val BLUETOOTH_CHANNELS = listOf(22, 23, 24, 25)

    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1

    /** 10 ms of mono audio at [SAMPLE_RATE]. */
    const val FRAME_SAMPLES = 480
    const val FRAME_MS = 10

    /** A session with no packets for this long is considered gone. */
    const val SESSION_TIMEOUT_MS = 3_000L

    const val MAX_PACKET_BYTES = 1500

    /**
     * Bitrate for [Codec.Opus]: about 12 KB/s instead of 96 KB/s of PCM, which is what lets
     * Bluetooth (and adb tunnels) keep up without stalls.
     */
    const val OPUS_BITRATE = 96_000

    /** Computer → phone audio ("use the phone as a speaker"): always Opus, stereo. */
    const val SPEAKER_CHANNELS = 2
    const val SPEAKER_BITRATE = 128_000
    const val MAX_NAME_CHARS = 64
}

enum class HostOs(val code: Int, val label: String) {
    Unknown(0, "Computer"),
    Windows(1, "Windows"),
    Linux(2, "Linux"),
    MacOs(3, "macOS");

    companion object {
        fun of(code: Int): HostOs = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/**
 * Audio encodings. The phone offers what it can send in [Packet.Hello.codecs] (a bit set) and the
 * computer picks one in [Packet.HelloAck.codec]. Both fields are optional trailing bytes, so older
 * builds simply see PCM.
 */
enum class Codec(val id: Int, val label: String) {
    Pcm(0, "PCM"),
    Opus(1, "Opus");

    val bit: Int get() = 1 shl id

    companion object {
        fun of(id: Int): Codec = entries.firstOrNull { it.id == id } ?: Pcm
    }
}

sealed class Packet {
    data class Discover(val nonce: Int) : Packet()

    data class Announce(val nonce: Int, val audioPort: Int, val os: HostOs, val name: String) : Packet()

    data class Hello(
        val sessionId: Int,
        val sampleRate: Int,
        val channels: Int,
        val deviceName: String,
        /** Bit set of [Codec.bit]; PCM is always supported. */
        val codecs: Int = Codec.Pcm.bit,
    ) : Packet()

    data class HelloAck(val sessionId: Int, val accepted: Boolean, val codec: Codec = Codec.Pcm) : Packet()

    class Audio(val sessionId: Int, val seq: Int, val samples: ShortArray) : Packet()

    /** One [PhoneInputProtocol.FRAME_MS] frame compressed with Opus. */
    class OpusAudio(val sessionId: Int, val seq: Int, val data: ByteArray) : Packet()

    data class Ping(val sessionId: Int, val timestampMs: Long) : Packet()

    data class Pong(val sessionId: Int, val timestampMs: Long) : Packet()

    data class Bye(val sessionId: Int) : Packet()

    /** Desktop → phone: whether this phone is the one currently feeding the virtual microphone. */
    data class State(val sessionId: Int, val active: Boolean) : Packet()

    /** Phone → desktop: start/stop sending the computer's audio to this phone. Repeated every second while on. */
    data class SpeakerRequest(val sessionId: Int, val on: Boolean) : Packet()

    /** Desktop → phone: one 10 ms frame of the computer's audio, Opus stereo. */
    class SpeakerAudio(val sessionId: Int, val seq: Int, val data: ByteArray) : Packet()

    /** Desktop → phone: whether computer audio is being sent; [message] explains a failure. */
    data class SpeakerState(val sessionId: Int, val on: Boolean, val message: String) : Packet()
}

object PacketCodec {
    private const val MAGIC_0 = 'P'.code
    private const val MAGIC_1 = 'I'.code

    private const val TYPE_DISCOVER = 1
    private const val TYPE_ANNOUNCE = 2
    private const val TYPE_HELLO = 3
    private const val TYPE_HELLO_ACK = 4
    private const val TYPE_AUDIO = 5
    private const val TYPE_PING = 6
    private const val TYPE_PONG = 7
    private const val TYPE_BYE = 8
    private const val TYPE_STATE = 9
    private const val TYPE_OPUS_AUDIO = 10
    private const val TYPE_SPEAKER_REQUEST = 11
    private const val TYPE_SPEAKER_AUDIO = 12
    private const val TYPE_SPEAKER_STATE = 13

    fun encode(packet: Packet): ByteArray {
        val w = ByteWriter(PhoneInputProtocol.MAX_PACKET_BYTES.coerceAtLeast(sizeHint(packet)))
        w.u8(MAGIC_0)
        w.u8(MAGIC_1)
        w.u8(PhoneInputProtocol.VERSION)
        when (packet) {
            is Packet.Discover -> {
                w.u8(TYPE_DISCOVER)
                w.i32(packet.nonce)
            }
            is Packet.Announce -> {
                w.u8(TYPE_ANNOUNCE)
                w.i32(packet.nonce)
                w.u16(packet.audioPort)
                w.u8(packet.os.code)
                w.str(packet.name)
            }
            is Packet.Hello -> {
                w.u8(TYPE_HELLO)
                w.i32(packet.sessionId)
                w.i32(packet.sampleRate)
                w.u8(packet.channels)
                w.str(packet.deviceName)
                w.u8(packet.codecs)
            }
            is Packet.HelloAck -> {
                w.u8(TYPE_HELLO_ACK)
                w.i32(packet.sessionId)
                w.u8(if (packet.accepted) 1 else 0)
                w.u8(packet.codec.id)
            }
            is Packet.Audio -> {
                w.u8(TYPE_AUDIO)
                w.i32(packet.sessionId)
                w.i32(packet.seq)
                w.pcm16le(packet.samples)
            }
            is Packet.SpeakerRequest -> {
                w.u8(TYPE_SPEAKER_REQUEST)
                w.i32(packet.sessionId)
                w.u8(if (packet.on) 1 else 0)
            }
            is Packet.SpeakerAudio -> {
                w.u8(TYPE_SPEAKER_AUDIO)
                w.i32(packet.sessionId)
                w.i32(packet.seq)
                w.bytes(packet.data)
            }
            is Packet.SpeakerState -> {
                w.u8(TYPE_SPEAKER_STATE)
                w.i32(packet.sessionId)
                w.u8(if (packet.on) 1 else 0)
                w.str(packet.message)
            }
            is Packet.OpusAudio -> {
                w.u8(TYPE_OPUS_AUDIO)
                w.i32(packet.sessionId)
                w.i32(packet.seq)
                w.bytes(packet.data)
            }
            is Packet.Ping -> {
                w.u8(TYPE_PING)
                w.i32(packet.sessionId)
                w.i64(packet.timestampMs)
            }
            is Packet.Pong -> {
                w.u8(TYPE_PONG)
                w.i32(packet.sessionId)
                w.i64(packet.timestampMs)
            }
            is Packet.Bye -> {
                w.u8(TYPE_BYE)
                w.i32(packet.sessionId)
            }
            is Packet.State -> {
                w.u8(TYPE_STATE)
                w.i32(packet.sessionId)
                w.u8(if (packet.active) 1 else 0)
            }
        }
        return w.toByteArray()
    }

    /** Returns null for anything that is not a well-formed PhoneInput packet of our version. */
    fun decode(data: ByteArray, length: Int = data.size): Packet? = try {
        val r = ByteReader(data, length.coerceAtMost(data.size))
        if (r.u8() != MAGIC_0 || r.u8() != MAGIC_1 || r.u8() != PhoneInputProtocol.VERSION) {
            null
        } else {
            when (r.u8()) {
                TYPE_DISCOVER -> Packet.Discover(r.i32())
                TYPE_ANNOUNCE -> Packet.Announce(r.i32(), r.u16(), HostOs.of(r.u8()), r.str())
                TYPE_HELLO -> Packet.Hello(r.i32(), r.i32(), r.u8(), r.str(), (r.optU8() ?: 0) or Codec.Pcm.bit)
                TYPE_HELLO_ACK -> Packet.HelloAck(r.i32(), r.u8() == 1, Codec.of(r.optU8() ?: Codec.Pcm.id))
                TYPE_AUDIO -> Packet.Audio(r.i32(), r.i32(), r.pcm16le())
                TYPE_OPUS_AUDIO -> Packet.OpusAudio(r.i32(), r.i32(), r.rest())
                TYPE_SPEAKER_REQUEST -> Packet.SpeakerRequest(r.i32(), r.u8() == 1)
                TYPE_SPEAKER_AUDIO -> Packet.SpeakerAudio(r.i32(), r.i32(), r.rest())
                TYPE_SPEAKER_STATE -> Packet.SpeakerState(r.i32(), r.u8() == 1, r.str())
                TYPE_PING -> Packet.Ping(r.i32(), r.i64())
                TYPE_PONG -> Packet.Pong(r.i32(), r.i64())
                TYPE_BYE -> Packet.Bye(r.i32())
                TYPE_STATE -> Packet.State(r.i32(), r.u8() == 1)
                else -> null
            }
        }
    } catch (_: MalformedPacketException) {
        null
    }

    private fun sizeHint(packet: Packet): Int = when (packet) {
        is Packet.Audio -> 12 + packet.samples.size * 2
        is Packet.OpusAudio -> 12 + packet.data.size
        is Packet.SpeakerAudio -> 12 + packet.data.size
        else -> 0
    }
}

private class MalformedPacketException : Exception()

private class ByteWriter(capacity: Int) {
    private val buf = ByteArray(capacity)
    private var size = 0

    fun u8(v: Int) {
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) {
        u8(v ushr 8)
        u8(v)
    }

    fun i32(v: Int) {
        u8(v ushr 24)
        u8(v ushr 16)
        u8(v ushr 8)
        u8(v)
    }

    fun i64(v: Long) {
        i32((v ushr 32).toInt())
        i32(v.toInt())
    }

    fun str(s: String) {
        var bytes = s.take(PhoneInputProtocol.MAX_NAME_CHARS).encodeToByteArray()
        if (bytes.size > 255) bytes = bytes.copyOf(255)
        u8(bytes.size)
        bytes.copyInto(buf, size)
        size += bytes.size
    }

    fun bytes(data: ByteArray) {
        data.copyInto(buf, size)
        size += data.size
    }

    fun pcm16le(samples: ShortArray) {
        for (s in samples) {
            val v = s.toInt()
            buf[size++] = v.toByte()
            buf[size++] = (v shr 8).toByte()
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

private class ByteReader(private val buf: ByteArray, private val end: Int) {
    private var pos = 0

    private fun need(n: Int) {
        if (end - pos < n) throw MalformedPacketException()
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    /** A trailing field added in a later build; null when the sender didn't include it. */
    fun optU8(): Int? = if (pos < end) u8() else null

    fun u16(): Int = (u8() shl 8) or u8()

    fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()

    fun i64(): Long = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFF_FFFFL)

    fun str(): String {
        val n = u8()
        need(n)
        val s = buf.decodeToString(pos, pos + n)
        pos += n
        return s
    }

    fun rest(): ByteArray = buf.copyOfRange(pos, end).also { pos = end }

    fun pcm16le(): ShortArray {
        val bytes = end - pos
        if (bytes % 2 != 0) throw MalformedPacketException()
        val out = ShortArray(bytes / 2)
        for (i in out.indices) {
            val lo = buf[pos++].toInt() and 0xFF
            val hi = buf[pos++].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }
}
