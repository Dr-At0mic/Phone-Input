package app.phoneinput.desktop.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameAssemblerTest {
    private fun collect(rate: Int, channels: Int, seconds: Int, chunk: Int, value: (frame: Int, ch: Int) -> Float): List<ShortArray> {
        val out = mutableListOf<ShortArray>()
        val asm = FrameAssembler(rate, channels) { out += it }
        var frame = 0
        while (frame < rate * seconds) {
            val n = minOf(chunk, rate * seconds - frame)
            val buf = FloatArray(n * channels) { i -> value(frame + i / channels, i % channels) }
            asm.push(buf, n)
            frame += n
        }
        return out
    }

    @Test
    fun resamples44100To48000Frames() {
        val frames = collect(44_100, 2, seconds = 2, chunk = 441) { _, _ -> 0.25f }
        assertTrue(frames.size in 199..200, "2 s should give ~200 frames of 10 ms, got ${frames.size}")
        assertTrue(frames.all { f -> f.size == 960 && f.all { it in 8_100..8_200 } })
    }

    @Test
    fun keepsLeftAndRightApart() {
        val frames = collect(48_000, 2, seconds = 1, chunk = 480) { _, ch -> if (ch == 0) 0.5f else -0.5f }
        assertTrue(frames.size in 99..100) // the interpolator holds back one sample
        val f = frames[10]
        assertTrue(f[0] > 16_000 && f[1] < -16_000)
    }

    @Test
    fun foldsSurroundCentreIntoBothSides() {
        // 5.1 order: FL, FR, FC, LFE, BL, BR — only the centre has sound.
        val frames = collect(48_000, 6, seconds = 1, chunk = 480) { _, ch -> if (ch == 2) 0.5f else 0f }
        val f = frames[10]
        assertTrue(f[0] > 10_000 && f[1] > 10_000, "centre should be heard on both sides: ${f[0]}, ${f[1]}")
    }

    @Test
    fun monoIsDuplicated() {
        val frames = collect(48_000, 1, seconds = 1, chunk = 1_000) { _, _ -> 0.5f }
        assertTrue(frames.size in 99..100) // the interpolator holds back one sample
        assertTrue(frames[5][0] == frames[5][1])
    }
}
