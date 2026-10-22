package app.phoneinput.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class NoiseSuppression(val key: String, val label: String, val description: String) {
    Off("off", "Off", "Send the microphone as it is"),
    Low("low", "Light", "Removes steady hum, keeps the room natural"),
    High("high", "Strong", "Removes fans and background noise, best for calls");

    companion object {
        fun of(key: String?): NoiseSuppression = entries.firstOrNull { it.key == key } ?: High
    }
}

/**
 * Software noise suppression for 10 ms / 480-sample frames at 48 kHz, so it works on every phone
 * (Android's own NoiseSuppressor is optional and many devices ignore it).
 *
 * Short-time spectral Wiener filter: 960-sample sqrt-Hann windows at 50% overlap, a 1024-point
 * FFT, a per-bin noise floor from minimum tracking of the smoothed power, and decision-directed
 * a-priori SNR to keep "musical noise" down. Output is delayed by one frame (10 ms).
 */
class SpectralNoiseSuppressor(private val hop: Int = 480) {
    private val window = hop * 2
    private val n = 1024
    private val bins = n / 2 + 1

    private val win = FloatArray(window) { sqrt(0.5f - 0.5f * cos(2 * PI * it / window).toFloat()) }
    private val input = FloatArray(window)
    private val overlap = FloatArray(hop)
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val fft = Fft(n)

    private val smoothed = FloatArray(bins)
    private val noise = FloatArray(bins)
    private val prevGain = FloatArray(bins) { 1f }
    private val prevPost = FloatArray(bins) { 1f }
    private var frames = 0

    /** Processes [frame] (exactly `hop` samples) in place. */
    fun process(frame: ShortArray, level: NoiseSuppression) {
        require(frame.size == hop) { "expected $hop samples" }
        input.copyInto(input, 0, hop, window)
        for (i in 0 until hop) input[hop + i] = frame[i].toFloat()

        for (i in 0 until window) re[i] = input[i] * win[i]
        re.fill(0f, window, n)
        im.fill(0f)
        fft.transform(re, im, inverse = false)

        val floor = if (level == NoiseSuppression.High) 0.08f else 0.25f
        // Minimum tracking underestimates the mean noise power, so the estimate is scaled up.
        val overSubtract = if (level == NoiseSuppression.High) 3.0f else 1.8f
        for (k in 0 until bins) {
            val power = re[k] * re[k] + im[k] * im[k] + 1e-3f
            smoothed[k] = if (frames == 0) power else 0.85f * smoothed[k] + 0.15f * power
            noise[k] = when {
                frames == 0 -> smoothed[k]
                smoothed[k] < noise[k] -> smoothed[k]
                else -> noise[k] * NOISE_RISE
            }
            val gain = if (level == NoiseSuppression.Off) {
                1f
            } else {
                val post = power / (overSubtract * noise[k])
                val prio = 0.98f * prevGain[k] * prevGain[k] * prevPost[k] + 0.02f * max(post - 1f, 0f)
                max(prio / (1f + prio), floor)
            }
            prevGain[k] = gain
            prevPost[k] = power / noise[k]
            re[k] *= gain
            im[k] *= gain
            if (k in 1 until n / 2) {
                re[n - k] *= gain
                im[n - k] *= gain
            }
        }
        frames++

        fft.transform(re, im, inverse = true)
        for (i in 0 until hop) {
            val v = overlap[i] + re[i] * win[i]
            frame[i] = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            overlap[i] = re[hop + i] * win[hop + i]
        }
    }

    fun reset() {
        input.fill(0f)
        overlap.fill(0f)
        prevGain.fill(1f)
        prevPost.fill(1f)
        frames = 0
    }

    private companion object {
        /** Noise floor may rise ~1.5 dB/s, so it follows a changing room but not speech. */
        const val NOISE_RISE = 1.0035f
    }
}

/** In-place iterative radix-2 complex FFT. The inverse is scaled by 1/n. */
internal class Fft(private val n: Int) {
    private val cosTable = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val reversed = IntArray(n).also { table ->
        val bits = n.countTrailingZeroBits()
        for (i in 0 until n) {
            var r = 0
            for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            table[i] = r
        }
    }

    init {
        require(n > 1 && n and (n - 1) == 0) { "n must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1f else -1f
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sign * sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val scale = 1f / n
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }
}
