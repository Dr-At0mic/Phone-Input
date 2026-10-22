package app.phoneinput.desktop.audio

import app.phoneinput.protocol.HostOs
import app.phoneinput.protocol.PhoneInputProtocol
import java.io.IOException

/**
 * Captures what the computer is playing (its default output), delivered as 10 ms frames of
 * 48 kHz stereo 16-bit audio, interleaved (960 samples), on the capture's own thread.
 */
interface SystemAudioCapture : AutoCloseable

object SystemAudio {
    /** Interleaved samples per 10 ms stereo frame. */
    const val FRAME_SAMPLES = PhoneInputProtocol.FRAME_SAMPLES * PhoneInputProtocol.SPEAKER_CHANNELS

    /**
     * Starts capturing. Throws IOException with a user-facing message when it can't start;
     * later failures (device gone, etc.) are reported through [onError].
     */
    fun open(os: HostOs, onFrame: (ShortArray) -> Unit, onError: (String) -> Unit): SystemAudioCapture = when (os) {
        HostOs.Windows -> WasapiLoopback(onFrame, onError)
        HostOs.Linux -> PulseMonitorCapture(onFrame, onError)
        else -> throw IOException("Not supported on this system")
    }
}

/**
 * Turns whatever the capture device delivers (any rate, any channel count, float) into 10 ms
 * frames of 48 kHz stereo shorts. Resamples by linear interpolation, which is fine for monitoring
 * and keeps the cost negligible; extra channels are folded into left/right.
 */
class FrameAssembler(
    sourceRate: Int,
    private val sourceChannels: Int,
    private val onFrame: (ShortArray) -> Unit,
) {
    private val step = sourceRate.toDouble() / PhoneInputProtocol.SAMPLE_RATE

    /** Position of the next output sample in source frames, relative to the current chunk (-1 = previous chunk's last). */
    private var pos = 0.0
    private var prevL = 0f
    private var prevR = 0f
    private var out = ShortArray(SystemAudio.FRAME_SAMPLES)
    private var outPos = 0

    /** [samples] holds [frames] interleaved frames of [sourceChannels] floats in -1..1. */
    fun push(samples: FloatArray, frames: Int) {
        if (frames <= 0) return
        while (true) {
            val i = kotlin.math.floor(pos).toInt()
            if (i + 1 >= frames) break
            val f = (pos - i).toFloat()
            val l0 = if (i < 0) prevL else left(samples, i)
            val r0 = if (i < 0) prevR else right(samples, i)
            emit(l0 + (left(samples, i + 1) - l0) * f, r0 + (right(samples, i + 1) - r0) * f)
            pos += step
        }
        pos -= frames
        prevL = left(samples, frames - 1)
        prevR = right(samples, frames - 1)
    }

    private fun left(s: FloatArray, frame: Int): Float {
        val base = frame * sourceChannels
        return if (sourceChannels >= 3) s[base] + CENTER_MIX * s[base + 2] else s[base]
    }

    private fun right(s: FloatArray, frame: Int): Float {
        val base = frame * sourceChannels
        return when {
            sourceChannels >= 3 -> s[base + 1] + CENTER_MIX * s[base + 2]
            sourceChannels == 2 -> s[base + 1]
            else -> s[base]
        }
    }

    private fun emit(l: Float, r: Float) {
        out[outPos++] = toShort(l)
        out[outPos++] = toShort(r)
        if (outPos == out.size) {
            onFrame(out)
            out = ShortArray(SystemAudio.FRAME_SAMPLES)
            outPos = 0
        }
    }

    private fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()

    private companion object {
        /** Surround layouts are FL, FR, FC, …: fold the centre (dialogue) into both sides. */
        const val CENTER_MIX = 0.7f
    }
}
