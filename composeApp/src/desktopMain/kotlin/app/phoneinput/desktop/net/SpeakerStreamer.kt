package app.phoneinput.desktop.net

import app.phoneinput.desktop.Log
import app.phoneinput.desktop.audio.SystemAudio
import app.phoneinput.desktop.audio.SystemAudioCapture
import app.phoneinput.protocol.HostOs
import app.phoneinput.protocol.PhoneInputProtocol
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * "Use the phone as a speaker": while at least one phone asks for it, captures this computer's
 * audio and sends it to those phones as stereo Opus via [StreamServer.sendSpeaker].
 *
 * A pacer sends exactly one frame every 10 ms: captured audio when there is some, silence when
 * the computer is quiet (Windows delivers nothing at all then). The phone's jitter buffer relies
 * on that steady cadence; a backlog beyond a few frames is dropped to keep latency low.
 */
class SpeakerStreamer(private val server: StreamServer, private val os: HostOs) : AutoCloseable {
    private val lock = Any()
    private var capture: SystemAudioCapture? = null
    @Volatile private var pacing = false
    private val captured = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)

    /** Called by the server whenever the number of listening phones changes. */
    fun onListeners(count: Int) {
        synchronized(lock) {
            if (count > 0 && capture == null) start()
            else if (count == 0 && capture != null) stop()
        }
    }

    private fun start() {
        captured.clear()
        capture = try {
            SystemAudio.open(os, onFrame = ::onCaptured, onError = ::onCaptureError)
        } catch (e: IOException) {
            Log.w("Computer audio unavailable: ${e.message}")
            server.speakerFailed(e.message ?: "Couldn't capture this computer's audio")
            return
        }
        Log.i("Sending computer audio to phones")
        pacing = true
        thread(name = "pm-speaker", isDaemon = true, priority = Thread.MAX_PRIORITY) { paceLoop() }
    }

    private fun stop() {
        pacing = false
        capture?.let { runCatching { it.close() } }
        capture = null
        Log.i("Stopped sending computer audio")
    }

    private fun onCaptured(frame: ShortArray) {
        while (!captured.offer(frame)) captured.poll()
    }

    private fun onCaptureError(message: String) {
        server.speakerFailed(message)
        synchronized(lock) { stop() }
    }

    private fun paceLoop() {
        val encoder = OpusEncoder(PhoneInputProtocol.SAMPLE_RATE, PhoneInputProtocol.SPEAKER_CHANNELS, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY).apply {
            bitrate = PhoneInputProtocol.SPEAKER_BITRATE
        }
        val silence = ShortArray(SystemAudio.FRAME_SAMPLES)
        val out = ByteArray(1_500)
        val frameNanos = PhoneInputProtocol.FRAME_MS * 1_000_000L
        var seq = 0
        var next = System.nanoTime()
        while (pacing) {
            next += frameNanos
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait) else if (wait < -100_000_000L) next = System.nanoTime() // fell far behind (sleep/suspend)

            while (captured.size > MAX_BACKLOG) captured.poll()
            val frame = captured.poll() ?: silence
            val n = encoder.encode(frame, 0, PhoneInputProtocol.FRAME_SAMPLES, out, 0, out.size)
            server.sendSpeaker(seq++, out.copyOf(n))
        }
    }

    override fun close() {
        synchronized(lock) { if (capture != null) stop() }
    }

    private companion object {
        const val QUEUE_FRAMES = 16
        /** Capture and pacer clocks differ slightly; never let more than 30 ms pile up between them. */
        const val MAX_BACKLOG = 3
    }
}
