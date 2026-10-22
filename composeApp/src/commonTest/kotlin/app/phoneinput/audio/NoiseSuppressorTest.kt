package app.phoneinput.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class NoiseSuppressorTest {
    private class Lcg(private var state: Long = 12345) {
        fun next(): Float {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            return ((state ushr 33).toInt() / Int.MAX_VALUE.toFloat()) * 2f - 1f
        }
    }

    private fun rmsDb(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return 10 * log10(s / x.size + 1e-9)
    }

    /** Runs [seconds] of the signal through the suppressor; returns input and output of the last second. */
    private fun run(level: NoiseSuppression, seconds: Int, signal: (Int) -> Float): Pair<FloatArray, FloatArray> {
        val ns = SpectralNoiseSuppressor()
        val total = seconds * 100
        val inLast = FloatArray(48_000)
        val outLast = FloatArray(48_000)
        val frame = ShortArray(480)
        for (f in 0 until total) {
            for (i in 0 until 480) frame[i] = signal(f * 480 + i).toInt().toShort()
            val keep = f >= total - 100
            if (keep) for (i in 0 until 480) inLast[(f - (total - 100)) * 480 + i] = frame[i].toFloat()
            ns.process(frame, level)
            if (keep) for (i in 0 until 480) outLast[(f - (total - 100)) * 480 + i] = frame[i].toFloat()
        }
        return inLast to outLast
    }

    @Test
    fun steadyNoiseIsStronglyReduced() {
        val rng = Lcg()
        val (input, output) = run(NoiseSuppression.High, 4) { rng.next() * 2_000f }
        val reduction = rmsDb(input) - rmsDb(output)
        assertTrue(reduction > 12, "noise reduced by only ${"%.1f".format(reduction)} dB")
    }

    /** Speech-like: a tone that starts and stops (300 ms on / 300 ms off) over steady noise. */
    @Test
    fun intermittentToneAboveNoiseIsKept() {
        val rng = Lcg(7)
        fun on(i: Int) = (i / 14_400) % 2 == 0
        fun tone(i: Int) = if (on(i)) 8_000f * sin(2 * PI * 1_000 * i / 48_000).toFloat() else 0f
        val seconds = 4
        val (_, output) = run(NoiseSuppression.High, seconds) { i -> tone(i) + rng.next() * 300f }
        val start = (seconds - 1) * 48_000
        // Compare only the "on" stretches, skipping 20 ms at each edge (and the 10 ms delay).
        val ref = ArrayList<Float>()
        val got = ArrayList<Float>()
        for (j in 0 until 48_000) {
            val i = start + j - 480
            if (on(i) && on(i - 960) && on(i + 960)) {
                ref += tone(i)
                got += output[j]
            }
        }
        val loss = rmsDb(ref.toFloatArray()) - rmsDb(got.toFloatArray())
        assertTrue(abs(loss) < 2, "tone level changed by ${"%.1f".format(loss)} dB")
    }

    @Test
    fun offIsTransparentApartFromOneFrameDelay() {
        val (input, output) = run(NoiseSuppression.Off, 2) { i -> 5_000f * sin(2 * PI * 440 * i / 48_000).toFloat() }
        var maxErr = 0f
        for (i in 480 until input.size) maxErr = maxOf(maxErr, abs(output[i] - input[i - 480]))
        assertTrue(maxErr <= 2f, "max reconstruction error $maxErr")
    }
}
