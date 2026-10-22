package app.phoneinput.android.stream

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.phoneinput.android.AppSettings
import app.phoneinput.android.Host
import app.phoneinput.android.MicSource
import app.phoneinput.android.Transport
import app.phoneinput.audio.AudioLevels
import app.phoneinput.audio.LevelHistory
import app.phoneinput.audio.SpectralNoiseSuppressor
import app.phoneinput.protocol.Codec
import app.phoneinput.protocol.Packet
import app.phoneinput.protocol.PhoneInputProtocol
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Captures the microphone and streams 10 ms PCM frames to [host] over Wi-Fi (UDP), USB
 * (framed TCP through `adb reverse`, see [TcpLink]) or Bluetooth (framed RFCOMM, see [BluetoothLink]).
 *
 * The capture thread owns the AudioRecord and the link: it says Hello until the desktop
 * accepts, then sends Audio every frame and a Ping every second. A second thread reads
 * HelloAck / Pong / Bye. If Pongs stop for [PhoneInputProtocol.SESSION_TIMEOUT_MS] the link is
 * "reconnecting" and Hello is resent; without auto-reconnect the session ends after 5 s.
 */
class StreamEngine(
    private val context: Context,
    private val host: Host,
    private val settings: AppSettings,
    private val deviceName: String,
    private val onFinished: (error: String?) -> Unit,
) {
    /** Settings that need the AudioRecord reopened. Noise suppression is ours and applies live. */
    private data class AudioConfig(val source: MicSource, val aec: Boolean, val agc: Boolean)

    @Volatile private var running = false
    @Volatile private var acked = false
    @Volatile private var everAcked = false
    @Volatile private var rejected = false
    @Volatile private var hostEnded = false
    @Volatile private var lastPongMs = 0L
    @Volatile private var rttMs: Int? = null

    /** A link being opened off the capture thread, and the result for the capture thread to pick up. */
    @Volatile private var opening = false
    @Volatile private var opened: Link? = null
    @Volatile private var linkError: String? = null

    /** What the computer chose in its HelloAck; Opus only if both sides allow it. */
    @Volatile private var codec = Codec.Pcm
    @Volatile private var resetEncoder = false

    /** Plays the computer's audio while the user has "Play computer audio here" on. */
    @Volatile private var speakerPlayer: SpeakerPlayer? = null

    /** Whether the computer uses this phone as its microphone (vs standby). */
    @Volatile private var activeOnHost = true

    private val sessionId = Random.nextInt()
    private val suppressor = SpectralNoiseSuppressor(PhoneInputProtocol.FRAME_SAMPLES)
    private var captureThread: Thread? = null

    fun start() {
        running = true
        captureThread = thread(name = "pm-capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCapture()
        }
    }

    /** Stops streaming and waits briefly for the capture thread to say Bye. */
    fun stop() {
        running = false
        captureThread?.join(1_000)
    }

    @SuppressLint("MissingPermission") // The UI only starts streaming after RECORD_AUDIO is granted.
    private fun runCapture() {
        var error: String? = null
        var record: AudioRecord? = null
        var effects: List<AudioEffect> = emptyList()
        var link: Link? = null
        var lastLinkAttempt = 0L
        var endedByUser = true

        try {
            Log.i(TAG, "Streaming to ${host.name} at ${host.key} over ${host.transport.label} as \"$deviceName\" (session $sessionId)")

            val frame = ShortArray(PhoneInputProtocol.FRAME_SAMPLES)
            val codecs = if (settings.compression.value) Codec.Pcm.bit or Codec.Opus.bit else Codec.Pcm.bit
            var encoder: OpusEncoder? = null
            val encoded = ByteArray(MAX_OPUS_BYTES)
            val history = LevelHistory(48)
            var config: AudioConfig? = null
            var seq = 0
            var lastHello = 0L
            var lastPing = 0L
            var speakerSent: Boolean? = null
            var lastPublish = 0L
            var lostSince = 0L

            while (running) {
                val wanted = AudioConfig(
                    settings.micSource.value,
                    settings.echoCancellation.value,
                    settings.autoGain.value,
                )
                if (record == null || wanted != config) {
                    effects.forEach { runCatching { it.release() } }
                    record?.let { runCatching { it.stop() }; it.release() }
                    Log.i(TAG, "Opening microphone: $wanted")
                    record = openRecord(wanted.source)
                    effects = attachEffects(record.audioSessionId, wanted)
                    config = wanted
                    suppressor.reset()
                }

                val n = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IOException("Microphone read failed ($n)")
                if (n == 0) continue
                if (n == frame.size) suppressor.process(frame, settings.noiseSuppression.value)

                val now = SystemClock.elapsedRealtime()
                val muted = StreamController.state.value.muted
                val db = if (muted) AudioLevels.FLOOR_DB else AudioLevels.rmsDbfs(frame, n)
                history.push(AudioLevels.toMeter(db))
                if (muted) frame.fill(0, 0, n)

                // (Re)open the link: once for UDP; for USB/Bluetooth whenever the stream drops.
                if (link is FramedLink && link.broken) {
                    Log.i(TAG, "${host.transport.label} link closed")
                    runCatching { link.close() }
                    link = null
                    acked = false
                }
                opened?.let {
                    link = it
                    opened = null
                }
                if (link == null && !opening && now - lastLinkAttempt >= 1_000) {
                    lastLinkAttempt = now
                    openLinkAsync()
                }

                val l = link
                if (acked && now - lastPongMs > PhoneInputProtocol.SESSION_TIMEOUT_MS) acked = false
                if (l != null && !acked && now - lastHello >= 500) {
                    if (now - lastHello > 2_000) Log.d(TAG, "Sending Hello via $l")
                    l.send(Packet.Hello(sessionId, PhoneInputProtocol.SAMPLE_RATE, PhoneInputProtocol.CHANNELS, deviceName, codecs))
                    lastHello = now
                }
                if (l != null && acked) {
                    if (codec == Codec.Opus) {
                        val enc = encoder ?: newEncoder().also { encoder = it }
                        if (resetEncoder) {
                            enc.resetState()
                            resetEncoder = false
                        }
                        if (n < frame.size) frame.fill(0, n) // Opus needs whole frames
                        val bytes = enc.encode(frame, 0, frame.size, encoded, 0, encoded.size)
                        l.send(Packet.OpusAudio(sessionId, seq++, encoded.copyOf(bytes)))
                    } else {
                        l.send(Packet.Audio(sessionId, seq++, if (n == frame.size) frame else frame.copyOf(n)))
                    }
                }
                // Computer audio: the request repeats with every Ping so it survives lost packets and reconnects.
                val wantSpeaker = StreamController.state.value.speaker
                if (wantSpeaker && speakerPlayer == null) speakerPlayer = SpeakerPlayer()
                if (!wantSpeaker) speakerPlayer?.let {
                    speakerPlayer = null
                    it.release()
                }
                if (l != null && acked && wantSpeaker != speakerSent) {
                    l.send(Packet.SpeakerRequest(sessionId, wantSpeaker))
                    speakerSent = wantSpeaker
                }
                if (l != null && now - lastPing >= 1_000) {
                    l.send(Packet.Ping(sessionId, now))
                    if (acked) l.send(Packet.SpeakerRequest(sessionId, wantSpeaker))
                    lastPing = now
                }

                if (rejected) {
                    error = "${host.name} can't take more devices right now."
                    endedByUser = false
                    break
                }
                if (hostEnded) {
                    error = "${host.name} ended the session."
                    endedByUser = false
                    break
                }

                val link = when {
                    acked && !activeOnHost -> LinkState.Standby
                    acked -> LinkState.Live
                    everAcked -> LinkState.Reconnecting
                    else -> LinkState.Connecting
                }
                if (link == LinkState.Live || link == LinkState.Standby) {
                    lostSince = 0L
                } else {
                    if (lostSince == 0L) lostSince = now
                    // Auto-reconnect only applies to a session that worked; a computer that never
                    // answered is reported quickly instead of showing "Connecting…" forever.
                    val giveUpAfter = when {
                        !everAcked && host.transport == Transport.Bluetooth -> BLUETOOTH_CONNECT_TIMEOUT_MS
                        !everAcked -> FIRST_CONNECT_TIMEOUT_MS
                        settings.autoReconnect.value -> Long.MAX_VALUE
                        else -> 5_000L
                    }
                    if (now - lostSince > giveUpAfter) {
                        error = when {
                            everAcked -> "Lost connection to ${host.name}."
                            host.transport == Transport.Bluetooth -> "Couldn't reach ${host.name} over Bluetooth" +
                                (linkError?.let { " ($it)" } ?: "") + ". Make sure it's paired with this phone, its Bluetooth is on, " +
                                "and PhoneInput is open on it."
                            host.usb -> "Couldn't reach the computer over USB. Connect the cable, turn on USB debugging " +
                                "(Developer options), allow this computer, and keep PhoneInput open on it."
                            else -> "${host.name} (${host.address}) didn't answer. Make sure PhoneInput is open on the computer " +
                                "and allowed through its firewall (UDP ${host.port})."
                        }
                        endedByUser = false
                        break
                    }
                }

                if (now - lastPublish >= 50) {
                    lastPublish = now
                    val levels = history.snapshot()
                    StreamController.update { it.copy(link = link, levelDb = db, levels = levels, rttMs = rttMs, codec = codec.takeIf { acked }) }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission missing", e)
            error = "PhoneInput needs microphone permission."
            endedByUser = false
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            error = e.message ?: "Streaming stopped unexpectedly."
            endedByUser = false
        } finally {
            speakerPlayer?.release()
            speakerPlayer = null
            opened?.let { runCatching { it.close() } }
            link?.let {
                if (acked) it.send(Packet.Bye(sessionId))
                runCatching { it.close() }
            }
            effects.forEach { runCatching { it.release() } }
            record?.let {
                runCatching { it.stop() }
                it.release()
            }
            running = false
            Log.i(TAG, "Streaming stopped" + (error?.let { ": $it" } ?: " by user"))
            if (!endedByUser) onFinished(error)
        }
    }

    /** Connecting can block for seconds (Bluetooth), so it runs off the capture thread. */
    private fun openLinkAsync() {
        opening = true
        thread(name = "pm-link-open", isDaemon = true) {
            try {
                val l = when (host.transport) {
                    Transport.WiFi -> UdpLink(host.address, host.port)
                    Transport.Usb -> TcpLink(host.address, host.port)
                    Transport.Bluetooth -> BluetoothLink.open(context, host.address)
                }
                if (!running) {
                    l.close()
                    return@thread
                }
                Log.i(TAG, "Link open: $l")
                linkError = null
                thread(name = "pm-receive", isDaemon = true) { runReceive(l) }
                opened = l
            } catch (e: Exception) {
                Log.d(TAG, "Link not available yet: ${e.message}")
                linkError = e.message
            } finally {
                opening = false
            }
        }
    }

    private fun runReceive(link: Link) {
        while (true) {
            val p = try {
                link.receive()
            } catch (_: IOException) {
                break // closed: stop() or a dropped USB link
            }
            when (p) {
                is Packet.HelloAck -> if (p.sessionId == sessionId) {
                    Log.i(TAG, "HelloAck accepted=${p.accepted} codec=${p.codec} via $link")
                    if (p.accepted) {
                        if (!acked) resetEncoder = true // the computer may have started a fresh decoder
                        codec = p.codec
                        lastPongMs = SystemClock.elapsedRealtime()
                        acked = true
                        everAcked = true
                    } else if (!everAcked) {
                        rejected = true
                    }
                }
                is Packet.Pong -> if (p.sessionId == sessionId) {
                    val now = SystemClock.elapsedRealtime()
                    lastPongMs = now
                    rttMs = (now - p.timestampMs).toInt()
                }
                is Packet.State -> if (p.sessionId == sessionId && p.active != activeOnHost) {
                    Log.i(TAG, if (p.active) "Computer is now using this device" else "On standby: computer is using another device")
                    activeOnHost = p.active
                }
                is Packet.SpeakerAudio -> if (p.sessionId == sessionId) speakerPlayer?.onPacket(p.seq, p.data)
                is Packet.SpeakerState -> if (p.sessionId == sessionId) {
                    val failed = !p.on && p.message.isNotEmpty()
                    if (failed) Log.w(TAG, "Computer audio unavailable: ${p.message}")
                    StreamController.update {
                        when {
                            failed -> it.copy(speaker = false, speakerOn = false, speakerMessage = "Computer audio: ${p.message}.")
                            it.speaker -> it.copy(speakerOn = p.on)
                            else -> it.copy(speakerOn = false)
                        }
                    }
                }
                is Packet.Bye -> if (p.sessionId == sessionId) {
                    Log.i(TAG, "Computer ended the session")
                    hostEnded = true
                }
                else -> Unit
            }
        }
    }

    /** Low-delay Opus (CELT only, 2.5 ms look-ahead): the codec adds almost nothing to latency. */
    private fun newEncoder() = OpusEncoder(PhoneInputProtocol.SAMPLE_RATE, PhoneInputProtocol.CHANNELS, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY).apply {
        bitrate = PhoneInputProtocol.OPUS_BITRATE
        complexity = 6
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(source: MicSource): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(
            PhoneInputProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 200 ms of headroom so a busy moment can't overflow the mic; reads still return every 10 ms.
        val bufferBytes = maxOf(minBytes, PhoneInputProtocol.FRAME_SAMPLES * 2 * 20)
        val record = AudioRecord(
            source.androidSource,
            PhoneInputProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Couldn't open the ${source.label.lowercase()}.")
        }
        record.startRecording()
        return record
    }

    private fun attachEffects(audioSession: Int, config: AudioConfig): List<AudioEffect> = buildList {
        if (config.aec && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioSession)?.let { it.enabled = true; add(it) }
        if (config.agc && AutomaticGainControl.isAvailable()) AutomaticGainControl.create(audioSession)?.let { it.enabled = true; add(it) }
    }

    private companion object {
        const val TAG = "PhoneInput"
        const val FIRST_CONNECT_TIMEOUT_MS = 8_000L
        const val MAX_OPUS_BYTES = 1_000

        /** Each Bluetooth attempt (SDP lookup, then the Linux channels) can take several seconds. */
        const val BLUETOOTH_CONNECT_TIMEOUT_MS = 25_000L
    }
}
