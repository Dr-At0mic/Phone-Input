package app.phoneinput.android.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import app.phoneinput.audio.JitterBuffer
import app.phoneinput.protocol.PhoneInputProtocol
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusException
import kotlin.concurrent.thread

/**
 * Plays the computer's audio on this phone ("use the phone as a speaker").
 *
 * [onPacket] runs on the receive thread: it decodes Opus in arrival order (filling short gaps with
 * Opus concealment) into a [JitterBuffer]. A playback thread writes one 10 ms frame at a time to a
 * low-latency AudioTrack, whose blocking write is the clock. Volume follows the media volume.
 */
class SpeakerPlayer {
    private val decoder = OpusDecoder(PhoneInputProtocol.SAMPLE_RATE, PhoneInputProtocol.SPEAKER_CHANNELS)
    private val jitter = JitterBuffer(targetFrames = 3)
    private var lastSeq: Int? = null

    @Volatile private var running = true
    private val worker = thread(name = "pm-speaker", isDaemon = true) { playLoop() }

    fun onPacket(seq: Int, data: ByteArray) {
        try {
            val last = lastSeq
            if (last != null && seq <= last) {
                if (last - seq < 1_000) return // duplicate or late
                jitter.reset() // the computer restarted its stream
            } else if (last != null && seq - last - 1 in 1..MAX_CONCEALED_FRAMES) {
                for (missing in last + 1 until seq) jitter.put(missing, decode(null))
            }
            lastSeq = seq
            jitter.put(seq, decode(data))
        } catch (e: OpusException) {
            Log.w(TAG, "Speaker decode failed: ${e.message}")
        }
    }

    private fun decode(data: ByteArray?): ShortArray {
        val pcm = ShortArray(FRAME_SAMPLES)
        decoder.decode(data, 0, data?.size ?: 0, pcm, 0, PhoneInputProtocol.FRAME_SAMPLES, false)
        return pcm
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBytes = AudioTrack.getMinBufferSize(PhoneInputProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PhoneInputProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, FRAME_SAMPLES * 2 * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val silence = ShortArray(FRAME_SAMPLES)
        try {
            track.play()
            Log.i(TAG, "Playing computer audio (track buffer ${track.bufferSizeInFrames} frames)")
            while (running) {
                val frame = jitter.take() ?: silence
                if (track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speaker playback failed", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    fun release() {
        running = false
        worker.join(500)
    }

    private companion object {
        const val TAG = "PhoneInput"
        const val FRAME_SAMPLES = PhoneInputProtocol.FRAME_SAMPLES * PhoneInputProtocol.SPEAKER_CHANNELS
        const val MAX_CONCEALED_FRAMES = 5
    }
}
