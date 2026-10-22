package app.phoneinput.desktop.net

import app.phoneinput.desktop.audio.AudioOutput
import app.phoneinput.desktop.audio.AudioPump
import app.phoneinput.audio.AudioLevels
import app.phoneinput.protocol.Codec
import app.phoneinput.protocol.HostOs
import app.phoneinput.protocol.Packet
import app.phoneinput.protocol.PacketCodec
import app.phoneinput.protocol.PhoneInputProtocol
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** End-to-end over UDP loopback: fake phones talk to the real server. */
class StreamServerTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    @AfterTest
    fun tearDown() = closeables.asReversed().forEach { runCatching { it.close() } }

    private class FakePhone(serverPort: Int, loopback: InetAddress) : AutoCloseable {
        val socket = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }
        private val target = InetSocketAddress(loopback, serverPort)

        fun send(p: Packet) {
            val b = PacketCodec.encode(p)
            socket.send(DatagramPacket(b, b.size, target))
        }

        fun receive(): Packet {
            val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)
            val dp = DatagramPacket(buf, buf.size)
            socket.receive(dp)
            return PacketCodec.decode(buf, dp.length) ?: fail("undecodable reply")
        }

        /** Next packet that isn't a State update. */
        fun receiveControl(): Packet {
            while (true) {
                val p = receive()
                if (p !is Packet.State) return p
            }
        }

        /** Next State update. */
        fun receiveState(): Packet.State {
            while (true) {
                val p = receive()
                if (p is Packet.State) return p
            }
        }

        fun hello(id: Int, name: String): Packet {
            send(Packet.Hello(id, 48_000, 1, name))
            return receiveControl()
        }

        override fun close() = socket.close()
    }

    private fun startServer(clock: () -> Long = System::currentTimeMillis) =
        StreamServer(listenPort = 0, usbPort = 0, clock = clock).closeLater().also { it.start() }

    private fun phone(server: StreamServer) = FakePhone(server.boundPort, loopback).closeLater()

    private fun waitFor(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("condition not met within $timeoutMs ms")
            Thread.sleep(5)
        }
    }

    @Test
    fun helloAudioPingByeLifecycle() {
        val server = startServer()
        val phone = phone(server)

        assertEquals(Packet.HelloAck(42, true), phone.hello(42, "Pixel 8"))
        assertEquals(Packet.State(42, true), phone.receiveState(), "first phone becomes active")
        waitFor { server.snapshot.value.active?.name == "Pixel 8" }

        for (seq in 0 until 6) phone.send(Packet.Audio(42, seq, ShortArray(480) { (seq * 100 + 1).toShort() }))
        var frame: ShortArray? = null
        waitFor { server.takeFrame()?.also { frame = it } != null }
        assertEquals(1, frame!![0].toInt(), "first frame plays first")

        phone.send(Packet.Ping(42, 123L))
        assertEquals(Packet.Pong(42, 123L), phone.receiveControl())

        phone.send(Packet.Bye(42))
        waitFor { server.snapshot.value.active == null }
        val entry = server.snapshot.value.devices.single()
        assertEquals("Pixel 8", entry.name)
        assertEquals(false, entry.connected)
    }

    @Test
    fun secondPhoneJoinsOnStandbyAndCanBeSelected() {
        val server = startServer()
        val phone = phone(server)
        val tablet = phone(server)

        assertEquals(Packet.HelloAck(1, true), phone.hello(1, "Phone"))
        assertEquals(Packet.State(1, true), phone.receiveState())
        assertEquals(Packet.HelloAck(2, true), tablet.hello(2, "Tablet"))
        assertEquals(Packet.State(2, false), tablet.receiveState(), "second device waits on standby")

        waitFor { server.snapshot.value.connected.size == 2 }
        assertEquals("Phone", server.snapshot.value.active?.name)

        // Only the active device's audio reaches the virtual mic.
        for (seq in 0 until 6) tablet.send(Packet.Audio(2, seq, ShortArray(480) { 3000 }))
        Thread.sleep(100)
        assertNull(server.takeFrame(), "standby audio is not played")
        waitFor { (server.levels()[2] ?: -60f) > -60f }

        server.select(2)
        assertEquals(Packet.State(2, true), tablet.receiveState())
        assertEquals(Packet.State(1, false), phone.receiveState())
        assertEquals("Tablet", server.snapshot.value.active?.name)

        for (seq in 6 until 12) tablet.send(Packet.Audio(2, seq, ShortArray(480) { 3000 }))
        var frame: ShortArray? = null
        waitFor { server.takeFrame()?.also { frame = it } != null }
        assertEquals(3000, frame!![0].toInt())
    }

    @Test
    fun whenActiveDeviceLeavesAnotherTakesOver() {
        val server = startServer()
        val phone = phone(server)
        val tablet = phone(server)
        phone.hello(1, "Phone")
        tablet.hello(2, "Tablet")
        waitFor { server.snapshot.value.connected.size == 2 }

        phone.send(Packet.Bye(1))
        waitFor { server.snapshot.value.active?.name == "Tablet" }
        assertEquals(Packet.State(2, true), tablet.receiveState().let { if (!it.active) tablet.receiveState() else it })
    }

    @Test
    fun sessionTimesOutAndPingsFromStaleSessionGetNoPong() {
        var now = 1_000_000L
        val server = startServer(clock = { now })
        val phone = phone(server)
        assertIs<Packet.HelloAck>(phone.hello(7, "Galaxy"))
        phone.receiveState()

        now += PhoneInputProtocol.SESSION_TIMEOUT_MS + 1
        server.checkTimeouts()
        assertNull(server.snapshot.value.active)

        phone.send(Packet.Ping(7, 1L))
        try {
            phone.socket.soTimeout = 300
            fail("expected no reply for an ended session, got ${phone.receive()}")
        } catch (_: SocketTimeoutException) {
        }

        // Saying Hello again restores the session.
        phone.socket.soTimeout = 2_000
        assertEquals(Packet.HelloAck(7, true), phone.hello(7, "Galaxy"))
    }

    @Test
    fun usbPhoneOverFramedTcpIsASessionUntilTheCableCloses() {
        val server = startServer()
        val tcp = java.net.Socket(loopback, server.usbBoundPort).closeLater().apply { soTimeout = 2_000 }
        val out = java.io.DataOutputStream(tcp.getOutputStream())
        val input = java.io.DataInputStream(tcp.getInputStream())
        fun send(p: Packet) = out.write(app.phoneinput.protocol.StreamFraming.frame(PacketCodec.encode(p)))
        fun receive(): Packet {
            val len = input.readUnsignedShort()
            val buf = ByteArray(len).also { input.readFully(it) }
            return PacketCodec.decode(buf) ?: fail("undecodable")
        }

        send(Packet.Hello(11, 48_000, 1, "Cable Phone"))
        assertEquals(Packet.HelloAck(11, true), receive())
        assertEquals(Packet.State(11, true), receive())
        val device = server.snapshot.value.active!!
        assertEquals(Transport.Usb, device.transport)
        assertEquals("USB", device.address)

        for (seq in 0 until 6) send(Packet.Audio(11, seq, ShortArray(480) { 1234 }))
        var frame: ShortArray? = null
        waitFor { server.takeFrame()?.also { frame = it } != null }
        assertEquals(1234, frame!![0].toInt())

        tcp.close()
        waitFor { server.snapshot.value.active == null }
        assertEquals(false, server.snapshot.value.devices.single().connected)
    }

    @Test
    fun bluetoothStreamIsASessionUntilTheLinkCloses() {
        val server = startServer()
        // Piped streams stand in for an accepted RFCOMM connection.
        val toServer = java.io.PipedOutputStream()
        val serverIn = java.io.PipedInputStream(toServer, 64 * 1024)
        val fromServer = java.io.PipedInputStream(64 * 1024)
        val serverOut = java.io.PipedOutputStream(fromServer)
        val link = java.io.Closeable {
            serverIn.close()
            serverOut.close()
        }
        server.serve(StreamPeer(Transport.Bluetooth, serverIn, serverOut, link))
        val out = java.io.DataOutputStream(toServer)
        val input = java.io.DataInputStream(fromServer)
        fun send(p: Packet) = out.write(app.phoneinput.protocol.StreamFraming.frame(PacketCodec.encode(p)))
        fun receive(): Packet {
            val len = input.readUnsignedShort()
            val buf = ByteArray(len).also { input.readFully(it) }
            return PacketCodec.decode(buf) ?: fail("undecodable")
        }

        send(Packet.Hello(21, 48_000, 1, "BT Phone"))
        assertEquals(Packet.HelloAck(21, true), receive())
        assertEquals(Packet.State(21, true), receive())
        val device = server.snapshot.value.active!!
        assertEquals(Transport.Bluetooth, device.transport)
        assertEquals("Bluetooth", device.address)

        for (seq in 0 until 6) send(Packet.Audio(21, seq, ShortArray(480) { 4321 }))
        var frame: ShortArray? = null
        waitFor { server.takeFrame()?.also { frame = it } != null }
        assertEquals(4321, frame!![0].toInt())

        toServer.close() // the phone walked out of range
        waitFor { server.snapshot.value.active == null }
        assertEquals(false, server.snapshot.value.devices.single().connected)

        server.forgetRecent(21)
        assertTrue(server.snapshot.value.devices.isEmpty())
    }

    @Test
    fun opusPhoneIsDecodedAndShortGapsConcealed() {
        val server = startServer()
        val phone = phone(server)
        phone.send(Packet.Hello(31, 48_000, 1, "Opus Phone", Codec.Pcm.bit or Codec.Opus.bit))
        assertEquals(Packet.HelloAck(31, true, Codec.Opus), phone.receiveControl())
        assertEquals(Codec.Opus, server.snapshot.value.active!!.codec)

        // A 440 Hz tone, encoded the way the phone does it; seq 3 is "lost" on the way.
        val encoder = OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY).apply { bitrate = 96_000 }
        val out = ByteArray(1_000)
        for (seq in 0 until 12) {
            val pcm = ShortArray(480) { i -> (8_000 * kotlin.math.sin(2 * Math.PI * 440 * (seq * 480 + i) / 48_000)).toInt().toShort() }
            val n = encoder.encode(pcm, 0, 480, out, 0, out.size)
            if (seq != 3) phone.send(Packet.OpusAudio(31, seq, out.copyOf(n)))
        }

        waitFor { server.bufferedMs() >= 120 } // all 12 decoded (seq 3 concealed) before playout starts
        val frames = List(12) { server.takeFrame() }.filterNotNull()
        assertEquals(12, frames.size, "seq 3 was filled in by Opus concealment, not skipped")
        assertEquals(0, server.stats().concealed)
        val loud = frames.drop(2).map { AudioLevels.rmsDbfs(it) }
        assertTrue(loud.all { it > -20f }, "decoded tone levels $loud")
    }

    @Test
    fun speakerRequestSubscribesThePhoneToComputerAudio() {
        val server = startServer()
        val listeners = mutableListOf<Int>()
        server.onSpeakerListeners = { synchronized(listeners) { listeners += it } }
        val phone = phone(server)
        phone.hello(51, "Speaker Phone")

        phone.send(Packet.SpeakerRequest(51, true))
        assertEquals(Packet.SpeakerState(51, true, ""), phone.receiveControl())
        assertEquals(listOf(1), synchronized(listeners) { listeners.toList() })
        assertTrue(server.snapshot.value.active!!.speaker)

        server.sendSpeaker(7, byteArrayOf(1, 2, 3))
        val audio = assertIs<Packet.SpeakerAudio>(phone.receiveControl())
        assertEquals(7, audio.seq)
        assertEquals(listOf<Byte>(1, 2, 3), audio.data.toList())

        // Capture failing turns it off for everyone, with the reason.
        server.speakerFailed("No audio device")
        assertEquals(Packet.SpeakerState(51, false, "No audio device"), phone.receiveControl())
        assertEquals(listOf(1, 0), synchronized(listeners) { listeners.toList() })

        // Asking again works; leaving the session stops it.
        phone.send(Packet.SpeakerRequest(51, true))
        assertEquals(Packet.SpeakerState(51, true, ""), phone.receiveControl())
        phone.send(Packet.Bye(51))
        waitFor { synchronized(listeners) { listeners.last() } == 0 }
    }

    @Test
    fun secondInstanceCannotShareThePort() {
        val first = startServer()
        val second = StreamServer(listenPort = first.boundPort, usbPort = null).closeLater()
        assertFailsWith<java.net.BindException> { second.bind() }
    }

    @Test
    fun unsupportedFormatIsRejected() {
        val server = startServer()
        val phone = phone(server)
        phone.send(Packet.Hello(3, 44_100, 2, "Old phone"))
        assertEquals(Packet.HelloAck(3, false), phone.receive())
        assertNull(server.snapshot.value.active)
    }

    @Test
    fun discoveryAnswersProbes() {
        val responder = DiscoveryResponder("Studio-PC", HostOs.Windows, audioPort = { 5823 }, listenPort = 0).closeLater()
        responder.start()
        val sock = DatagramSocket(0, loopback).closeLater().apply { soTimeout = 2_000 }
        val probe = PacketCodec.encode(Packet.Discover(99))
        sock.send(DatagramPacket(probe, probe.size, InetSocketAddress(loopback, responder.boundPort)))
        val buf = ByteArray(1500)
        val dp = DatagramPacket(buf, buf.size)
        sock.receive(dp)
        assertEquals(Packet.Announce(99, 5823, HostOs.Windows, "Studio-PC"), PacketCodec.decode(buf, dp.length))
    }

    @Test
    fun pumpWritesPhoneAudioWithGainAndMute() {
        val server = startServer()
        val phone = phone(server)
        assertIs<Packet.HelloAck>(phone.hello(5, "Pixel"))

        val written = mutableListOf<Short>()
        val output = object : AudioOutput {
            override fun write(pcm: ByteArray, length: Int, blocking: Boolean): Boolean {
                synchronized(written) {
                    for (i in 0 until length / 2) {
                        written += ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort()
                    }
                }
                Thread.sleep(10) // behave like a clocked device
                return true
            }

            override fun close() {}
        }
        val muted = java.util.concurrent.atomic.AtomicBoolean(false)
        val meters = AtomicInteger()
        val pump = AudioPump(
            server = server,
            primaryFactory = { output },
            monitorFactory = { null },
            controls = { AudioPump.Controls(muted = muted.get(), gainDb = 6f, monitor = false) },
            onMeter = { meters.incrementAndGet() },
        ).closeLater()
        pump.start()

        for (seq in 0 until 20) phone.send(Packet.Audio(5, seq, ShortArray(480) { 1000 }))
        waitFor { synchronized(written) { written.any { it.toInt() != 0 } } }
        val boosted = synchronized(written) { written.first { it.toInt() != 0 }.toInt() }
        assertTrue(boosted in 1990..2000, "+6 dB ≈ ×2, got $boosted")

        muted.set(true)
        Thread.sleep(50)
        synchronized(written) { written.clear() }
        for (seq in 20 until 40) phone.send(Packet.Audio(5, seq, ShortArray(480) { 1000 }))
        Thread.sleep(200)
        assertTrue(synchronized(written) { written.isNotEmpty() && written.all { it.toInt() == 0 } }, "muted output is silence")
        assertTrue(meters.get() > 0, "meter updates published")
    }
}
