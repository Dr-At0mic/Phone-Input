package app.phoneinput.protocol

import app.phoneinput.audio.AudioLevels
import app.phoneinput.audio.Formats
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private fun roundTrip(p: Packet): Packet? = PacketCodec.decode(PacketCodec.encode(p))

    @Test
    fun controlPacketsRoundTrip() {
        val packets = listOf(
            Packet.Discover(-42),
            Packet.Announce(7, 5823, HostOs.Linux, "thinkpad-fedora"),
            Packet.Hello(123456789, 48_000, 1, "Pixel 8"),
            Packet.HelloAck(-1, true),
            Packet.HelloAck(5, false),
            Packet.Ping(9, 1_729_000_000_123L),
            Packet.Pong(9, Long.MAX_VALUE),
            Packet.Bye(77),
            Packet.State(77, true),
            Packet.State(-3, false),
            Packet.SpeakerRequest(5, true),
            Packet.SpeakerState(5, false, "Install pulseaudio-utils (parec)"),
        )
        packets.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun codecNegotiationRoundTripsAndOlderPacketsMeanPcm() {
        val hello = Packet.Hello(1, 48_000, 1, "Pixel", Codec.Pcm.bit or Codec.Opus.bit)
        assertEquals(hello, roundTrip(hello))
        assertEquals(Packet.HelloAck(1, true, Codec.Opus), roundTrip(Packet.HelloAck(1, true, Codec.Opus)))

        // Builds before codecs existed end Hello/HelloAck without the codec byte.
        val oldHello = PacketCodec.encode(hello).let { it.copyOf(it.size - 1) }
        assertEquals(Codec.Pcm.bit, assertIs<Packet.Hello>(PacketCodec.decode(oldHello)).codecs)
        val oldAck = PacketCodec.encode(Packet.HelloAck(1, true, Codec.Opus)).let { it.copyOf(it.size - 1) }
        assertEquals(Codec.Pcm, assertIs<Packet.HelloAck>(PacketCodec.decode(oldAck)).codec)
    }

    @Test
    fun opusPayloadSurvives() {
        val data = ByteArray(97) { (it * 7).toByte() }
        val decoded = assertIs<Packet.OpusAudio>(roundTrip(Packet.OpusAudio(4, 99, data)))
        assertEquals(4, decoded.sessionId)
        assertEquals(99, decoded.seq)
        assertContentEquals(data, decoded.data)
    }

    @Test
    fun audioSamplesSurviveIncludingExtremes() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 12345, -12345)
        val decoded = assertIs<Packet.Audio>(roundTrip(Packet.Audio(3, Int.MAX_VALUE, samples)))
        assertEquals(3, decoded.sessionId)
        assertEquals(Int.MAX_VALUE, decoded.seq)
        assertContentEquals(samples, decoded.samples)
    }

    @Test
    fun fullFrameFitsInOneDatagram() {
        val bytes = PacketCodec.encode(Packet.Audio(1, 1, ShortArray(PhoneInputProtocol.FRAME_SAMPLES)))
        assertEquals(12 + PhoneInputProtocol.FRAME_SAMPLES * 2, bytes.size)
        assertTrue(bytes.size <= PhoneInputProtocol.MAX_PACKET_BYTES)
    }

    @Test
    fun unicodeNamesAreKept() {
        val p = Packet.Hello(1, 48_000, 1, "Anandhu’s Galaxy · ഫോൺ")
        assertEquals(p, roundTrip(p))
    }

    @Test
    fun longNamesAreTruncated() {
        val decoded = assertIs<Packet.Hello>(roundTrip(Packet.Hello(1, 48_000, 1, "x".repeat(500))))
        assertEquals(PhoneInputProtocol.MAX_NAME_CHARS, decoded.deviceName.length)
    }

    @Test
    fun malformedInputIsRejected() {
        assertNull(PacketCodec.decode(ByteArray(0)))
        assertNull(PacketCodec.decode("hello world".encodeToByteArray()))
        val ok = PacketCodec.encode(Packet.Ping(1, 2))
        assertNull(PacketCodec.decode(ok, ok.size - 1), "truncated")
        val badVersion = ok.copyOf().also { it[2] = 99 }
        assertNull(PacketCodec.decode(badVersion))
        val oddAudio = PacketCodec.encode(Packet.Audio(1, 1, ShortArray(4)))
        assertNull(PacketCodec.decode(oddAudio, oddAudio.size - 1), "odd PCM byte count")
    }

    @Test
    fun levelsAndFormatting() {
        assertEquals(AudioLevels.FLOOR_DB, AudioLevels.rmsDbfs(ShortArray(480)))
        val fullScale = ShortArray(480) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertTrue(AudioLevels.rmsDbfs(fullScale) > -0.1f)
        assertEquals(0f, AudioLevels.toMeter(-90f))
        assertEquals(1f, AudioLevels.toMeter(0f))
        assertEquals("— dBFS", Formats.dbfs(AudioLevels.FLOOR_DB))
        assertEquals("−18 dBFS", Formats.dbfs(-18.2f))
        assertEquals("12:41", Formats.duration(761_000))
        assertEquals("1:00:05", Formats.duration(3_605_000))
        assertEquals("+4 dB", Formats.signedDb(4f))

        val s = shortArrayOf(20000, -20000)
        AudioLevels.applyGain(s, 2, 2f)
        assertContentEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE), s, "gain clips instead of wrapping")
    }
}
