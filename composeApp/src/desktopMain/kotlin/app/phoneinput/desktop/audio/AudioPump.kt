package app.phoneinput.desktop.audio

import app.phoneinput.audio.AudioLevels
import app.phoneinput.audio.LevelHistory
import app.phoneinput.desktop.net.StreamServer
import app.phoneinput.protocol.PhoneInputProtocol
import kotlin.concurrent.thread

/**
 * Real-time loop: every 10 ms takes a frame from the [StreamServer] (or silence), applies gain
 * and mute, and writes it to the virtual microphone and, optionally, the speakers.
 *
 * The virtual mic's blocking write is the clock. Without one (driver missing, or in tests)
 * the loop paces itself with the system clock.
 */
class AudioPump(
    private val server: StreamServer,
    private val primaryFactory: () -> AudioOutput?,
    private val monitorFactory: () -> AudioOutput? = { JavaSoundOutput.open(null) },
    private val controls: () -> Controls,
    private val onMeter: (Meter) -> Unit,
) : AutoCloseable {

    data class Controls(val muted: Boolean, val gainDb: Float, val monitor: Boolean)

    data class Meter(val levels: List<Float>, val rmsDb: Float, val peakDb: Float, val outputError: Boolean)

    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        running = true
        worker = thread(name = "pm-audio-pump", isDaemon = true, priority = Thread.MAX_PRIORITY) { loop() }
    }

    private fun loop() {
        val frameNanos = PhoneInputProtocol.FRAME_MS * 1_000_000L
        val silence = ShortArray(PhoneInputProtocol.FRAME_SAMPLES)
        val work = ShortArray(PhoneInputProtocol.FRAME_SAMPLES * 4)
        var bytes = ByteArray(work.size * 2)
        val history = LevelHistory(96)

        var primary: AudioOutput? = openSafely(primaryFactory)
        var primaryFailed = primary == null
        var lastPrimaryAttempt = System.currentTimeMillis()
        var monitor: AudioOutput? = null
        var deadline = System.nanoTime()
        var lastMeter = 0L
        var peakDb = AudioLevels.FLOOR_DB
        var rmsDb = AudioLevels.FLOOR_DB
        var wasLive = false

        try {
            while (running) {
                server.checkTimeouts()
                val c = controls()
                val live = server.hasActiveSession
                val frame = server.takeFrame() ?: silence
                val n = frame.size.coerceAtMost(work.size)
                frame.copyInto(work, 0, 0, n)
                AudioLevels.applyGain(work, n, AudioLevels.gainFactor(c.gainDb))

                if (live) {
                    rmsDb = AudioLevels.rmsDbfs(work, n)
                    history.push(AudioLevels.toMeter(rmsDb))
                    peakDb = maxOf(peakDb - 0.15f, AudioLevels.peakDbfs(work, n))
                } else if (wasLive) {
                    history.clear()
                    peakDb = AudioLevels.FLOOR_DB
                    rmsDb = AudioLevels.FLOOR_DB
                }
                wasLive = live

                if (c.muted) work.fill(0, 0, n)
                if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = work[i].toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }

                // Virtual microphone (clocked, blocking).
                val now = System.currentTimeMillis()
                if (primary == null && now - lastPrimaryAttempt > 2_000) {
                    lastPrimaryAttempt = now
                    primary = openSafely(primaryFactory)
                    primaryFailed = primary == null
                }
                if (primary != null && !primary.write(bytes, n * 2, blocking = true)) {
                    runCatching { primary.close() }
                    primary = null
                    primaryFailed = true
                }

                // Speakers, only while a phone is live.
                if (c.monitor && live) {
                    if (monitor == null) monitor = openSafely(monitorFactory)
                    monitor?.write(bytes, n * 2, blocking = primary == null)
                } else if (monitor != null) {
                    runCatching { monitor.close() }
                    monitor = null
                }

                if (primary == null && monitor == null) {
                    deadline += frameNanos
                    val wait = deadline - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    else if (wait < -100_000_000) deadline = System.nanoTime()
                } else {
                    deadline = System.nanoTime()
                }

                if (now - lastMeter >= 50) {
                    lastMeter = now
                    onMeter(Meter(history.snapshot(), rmsDb, peakDb, primaryFailed))
                }
            }
        } finally {
            runCatching { primary?.close() }
            runCatching { monitor?.close() }
        }
    }

    private fun openSafely(factory: () -> AudioOutput?): AudioOutput? = try {
        factory()
    } catch (_: Exception) {
        null
    }

    override fun close() {
        running = false
        worker?.join(500)
    }
}
