package app.phoneinput.audio

import app.phoneinput.protocol.PhoneInputProtocol
import kotlin.jvm.Synchronized
import kotlin.time.TimeSource

/**
 * Reorders incoming frames by sequence number and smooths network jitter.
 *
 * Playback starts once the target depth is queued. With [adaptive] on, the target follows how
 * unevenly frames arrive: each frame's arrival time minus its ideal time (seq × 10 ms) is tracked
 * over the last few seconds, and the spread of those offsets is how much buffer it takes to never
 * run dry. Wi-Fi (UDP) usually needs 30–50 ms; USB through adb and Bluetooth deliver in bursts
 * after short stalls and get more. When the link calms down, extra depth is trimmed one frame at a
 * time so latency comes back down.
 *
 * A missing frame is concealed by fading out the last good one (instead of a hard cut to
 * silence); if the queue runs dry the buffer re-primes. Thread-safe: one producer (network) and
 * one consumer (audio). Used for the phone's microphone on the computer and for the computer's
 * audio on the phone.
 */
class JitterBuffer(
    /** Minimum depth before playback starts (and the whole target when not [adaptive]). */
    private val targetFrames: Int = 3,
    /** Hard latency cap: older frames beyond this are dropped. */
    private val maxFrames: Int = 40,
    private val adaptive: Boolean = true,
    private val clockMs: () -> Long = monotonicMs(),
) {
    data class Stats(val played: Long, val concealed: Long, val dropped: Long)

    /** Holds at most [maxFrames] entries, so finding the oldest by scanning is cheap. */
    private val frames = HashMap<Int, ShortArray>()
    private var nextSeq: Int? = null
    private var primed = false
    private var played = 0L
    private var concealed = 0L
    private var dropped = 0L

    private var target = targetFrames
    private val offsets = LongArray(JITTER_WINDOW)
    private var offsetCount = 0
    private var offsetPos = 0

    /** Smallest depth seen since [trimWindowStartMs]; if it stays high, latency can be trimmed. */
    private var trimWindowStartMs = 0L
    private var trimWindowMin = Int.MAX_VALUE

    private var last: ShortArray? = null
    private var lostRun = 0

    @Synchronized
    fun reset() {
        frames.clear()
        nextSeq = null
        primed = false
        target = targetFrames
        offsetCount = 0
        offsetPos = 0
        trimWindowStartMs = 0L
        trimWindowMin = Int.MAX_VALUE
        last = null
        lostRun = 0
    }

    @Synchronized
    fun put(seq: Int, samples: ShortArray) {
        if (adaptive) recordArrival(seq)
        val next = nextSeq
        if (next != null && seq < next) {
            dropped++ // arrived after its slot was played
            return
        }
        frames[seq] = samples
        val cap = minOf(maxFrames, target * 2 + 8)
        while (frames.size > cap) {
            pollOldest()
            dropped++
            if (primed) nextSeq = oldestSeq()
        }
    }

    /** The next frame to play, a fading copy of the last one while frames are missing, or null for silence. */
    @Synchronized
    fun take(): ShortArray? {
        if (!primed) {
            if (frames.size < target) return conceal()
            primed = true
            nextSeq = oldestSeq()
            trimWindowStartMs = clockMs()
            trimWindowMin = Int.MAX_VALUE
        }
        if (adaptive) trimIfLagging()
        val seq = nextSeq ?: return conceal()
        val frame = frames.remove(seq)
        nextSeq = seq + 1
        if (frame != null) {
            played++
            last = frame
            lostRun = 0
            return frame
        }
        concealed++
        if (frames.isEmpty()) {
            primed = false // underrun: wait for the buffer to refill
            nextSeq = null
        }
        return conceal()
    }

    @Synchronized
    fun depthFrames(): Int = frames.size

    @Synchronized
    fun targetDepthFrames(): Int = target

    @Synchronized
    fun stats(): Stats = Stats(played, concealed, dropped)

    private fun oldestSeq(): Int = frames.keys.min()

    private fun pollOldest() {
        frames.remove(oldestSeq())
    }

    private fun recordArrival(seq: Int) {
        offsets[offsetPos] = clockMs() - seq.toLong() * PhoneInputProtocol.FRAME_MS
        offsetPos = (offsetPos + 1) % offsets.size
        if (offsetCount < offsets.size) offsetCount++
        if (offsetCount < MIN_SAMPLES) return
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (i in 0 until offsetCount) {
            val o = offsets[i]
            if (o < lo) lo = o
            if (o > hi) hi = o
        }
        val spreadFrames = ((hi - lo) + PhoneInputProtocol.FRAME_MS - 1) / PhoneInputProtocol.FRAME_MS
        target = (spreadFrames.toInt() + 1).coerceIn(targetFrames, minOf(MAX_TARGET, maxFrames / 2))
    }

    /** Drops one frame per second while the buffer never got close to empty: it's holding more latency than needed. */
    private fun trimIfLagging() {
        trimWindowMin = minOf(trimWindowMin, frames.size)
        val now = clockMs()
        if (now - trimWindowStartMs < TRIM_WINDOW_MS) return
        if (trimWindowMin > target + 1 && frames.size > 1) {
            pollOldest()
            nextSeq = oldestSeq()
            dropped++
        }
        trimWindowStartMs = now
        trimWindowMin = Int.MAX_VALUE
    }

    /** Fades the last good frame out over [FADE_FRAMES] frames, then silence. */
    private fun conceal(): ShortArray? {
        val l = last ?: return null
        if (lostRun >= FADE_FRAMES) {
            last = null
            return null
        }
        val from = 1f - lostRun.toFloat() / FADE_FRAMES
        val to = 1f - (lostRun + 1).toFloat() / FADE_FRAMES
        lostRun++
        return ShortArray(l.size) { i -> (l[i] * (from + (to - from) * i / l.size)).toInt().toShort() }
    }

    private companion object {
        fun monotonicMs(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }

        /** ~6 s of arrivals at 100 frames/s. */
        const val JITTER_WINDOW = 600
        const val MIN_SAMPLES = 50
        const val MAX_TARGET = 25
        const val TRIM_WINDOW_MS = 1_000L
        const val FADE_FRAMES = 4
    }
}
