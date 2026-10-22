package app.phoneinput.desktop.audio

import app.phoneinput.desktop.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Linux: records the default output's monitor source with `parec` (PulseAudio, and PipeWire
 * through pipewire-pulse), or with `pw-cat` on PipeWire without pulseaudio-utils, already as
 * 48 kHz stereo 16-bit.
 */
internal class PulseMonitorCapture(
    private val onFrame: (ShortArray) -> Unit,
    private val onError: (String) -> Unit,
) : SystemAudioCapture {
    @Volatile private var running = true

    private val process: Process = try {
        val command = if (!onPath("parec") && onPath("pw-cat")) {
            listOf(
                "pw-cat", "--record", "--raw", "--format=s16", "--rate=48000", "--channels=2", "--latency=20ms",
                "-P", "{ stream.capture.sink=true application.name=PhoneInput node.name=phoneinput_speaker }", "-",
            )
        } else {
            listOf(
                "parec", "--raw", "--format=s16le", "--rate=48000", "--channels=2", "--latency-msec=20",
                "--device=@DEFAULT_MONITOR@",
            )
        }
        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (e: IOException) {
        throw IOException("Install pipewire-bin (pw-cat) or pulseaudio-utils (parec)", e)
    }

    init {
        thread(name = "pm-parec", isDaemon = true, priority = Thread.MAX_PRIORITY) { readLoop() }
    }

    private fun readLoop() {
        val input = DataInputStream(process.inputStream.buffered())
        val bytes = ByteArray(SystemAudio.FRAME_SAMPLES * 2)
        try {
            while (running) {
                input.readFully(bytes)
                val frame = ShortArray(SystemAudio.FRAME_SAMPLES) { i ->
                    ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort()
                }
                onFrame(frame)
            }
        } catch (_: EOFException) {
            if (running) {
                Log.w("parec exited (code ${runCatching { process.waitFor() }.getOrNull()})")
                onError("Couldn't capture this computer's audio")
            }
        } catch (e: IOException) {
            if (running) onError("Couldn't capture this computer's audio")
        }
    }

    override fun close() {
        running = false
        process.destroy()
    }
}
