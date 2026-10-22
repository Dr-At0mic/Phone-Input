package app.phoneinput.desktop.audio

import app.phoneinput.protocol.PhoneInputProtocol
import java.io.IOException
import java.io.OutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/** A destination for 48 kHz mono s16le PCM. Blocking writes pace the caller in real time. */
interface AudioOutput : AutoCloseable {
    /**
     * Writes [length] bytes. With [blocking] false the write is skipped when the device buffer is
     * full, so a secondary output never slows the primary one. Returns false if the device failed.
     */
    fun write(pcm: ByteArray, length: Int, blocking: Boolean): Boolean
}

val PCM_FORMAT = AudioFormat(PhoneInputProtocol.SAMPLE_RATE.toFloat(), 16, PhoneInputProtocol.CHANNELS, true, false)

class JavaSoundOutput private constructor(private val line: SourceDataLine) : AudioOutput {
    override fun write(pcm: ByteArray, length: Int, blocking: Boolean): Boolean {
        if (!line.isOpen) return false
        if (!blocking && line.available() < length) return true
        line.write(pcm, 0, length)
        return true
    }

    override fun close() {
        line.stop()
        line.close()
    }

    companion object {
        private const val BUFFER_FRAMES = 6

        fun supports(mixer: Mixer.Info): Boolean =
            AudioSystem.getMixer(mixer).isLineSupported(DataLine.Info(SourceDataLine::class.java, PCM_FORMAT))

        /** Opens [mixer], or the system default output when null. */
        @Throws(LineUnavailableException::class)
        fun open(mixer: Mixer.Info?): JavaSoundOutput {
            val line = if (mixer == null) AudioSystem.getSourceDataLine(PCM_FORMAT) else AudioSystem.getSourceDataLine(PCM_FORMAT, mixer)
            line.open(PCM_FORMAT, PhoneInputProtocol.FRAME_SAMPLES * 2 * BUFFER_FRAMES)
            line.start()
            return JavaSoundOutput(line)
        }
    }
}

/** Pipes PCM into a player process (`pacat` or `pw-cat`) that plays it into a PulseAudio / PipeWire sink. */
class PipedOutput private constructor(private val process: Process) : AudioOutput {
    private val stdin: OutputStream = process.outputStream

    override fun write(pcm: ByteArray, length: Int, blocking: Boolean): Boolean = try {
        stdin.write(pcm, 0, length)
        stdin.flush()
        true
    } catch (_: IOException) {
        false
    }

    override fun close() {
        runCatching { stdin.close() }
        process.destroy()
    }

    companion object {
        /** PulseAudio, or PipeWire through pipewire-pulse (needs pulseaudio-utils). */
        fun pacat(sink: String): PipedOutput = start(
            "pacat", "--playback", "--raw",
            "--device=$sink",
            "--format=s16le",
            "--rate=${PhoneInputProtocol.SAMPLE_RATE}",
            "--channels=${PhoneInputProtocol.CHANNELS}",
            "--latency-msec=30",
            "--client-name=PhoneInput",
        )

        /** Native PipeWire (pipewire-bin, installed with PipeWire on Ubuntu, Fedora, etc.). */
        fun pwCat(target: String): PipedOutput = start(
            "pw-cat", "--playback", "--raw",
            "--target=$target",
            "--format=s16",
            "--rate=${PhoneInputProtocol.SAMPLE_RATE}",
            "--channels=${PhoneInputProtocol.CHANNELS}",
            "--latency=30ms",
            "-P", "{ application.name=PhoneInput node.name=phoneinput_playback }",
            "-",
        )

        private fun start(vararg command: String): PipedOutput = PipedOutput(
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start(),
        )
    }
}

/** True when [command] is an executable on PATH. */
fun onPath(command: String): Boolean = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
    .any { dir -> dir.isNotEmpty() && java.io.File(dir, command).canExecute() }
