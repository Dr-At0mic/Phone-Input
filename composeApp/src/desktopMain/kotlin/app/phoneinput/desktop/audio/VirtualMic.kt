package app.phoneinput.desktop.audio

import app.phoneinput.desktop.Log
import app.phoneinput.protocol.HostOs
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem

/** What the user should pick as the input device in other apps, or why that isn't possible yet. */
data class VirtualMicStatus(
    val ready: Boolean,
    val deviceName: String,
    val help: String? = null,
    val helpUrl: String? = null,
) {
    companion object {
        val Checking = VirtualMicStatus(ready = false, deviceName = "Checking…")
    }
}

/**
 * The system-level microphone other apps record from. v1 relies on existing drivers:
 * VB-CABLE on Windows, a PulseAudio/PipeWire virtual source on Linux.
 */
interface VirtualMic : AutoCloseable {
    val status: VirtualMicStatus

    /** Opens the device PhoneInput plays into, or null when unavailable. */
    fun openOutput(): AudioOutput?

    override fun close() {}

    companion object {
        fun forHost(os: HostOs): VirtualMic = when (os) {
            HostOs.Windows -> VbCableMic()
            HostOs.Linux -> if (!onPath("pactl") && onPath("pw-loopback")) PipeWireVirtualMic() else PulseVirtualMic()
            else -> UnsupportedMic(os)
        }
    }
}

/** Windows: play into "CABLE Input"; apps record from "CABLE Output". */
class VbCableMic : VirtualMic {
    private val cableInput = AudioSystem.getMixerInfo().firstOrNull {
        it.name.contains("CABLE Input", ignoreCase = true) && JavaSoundOutput.supports(it)
    }

    override val status: VirtualMicStatus = if (cableInput != null) {
        VirtualMicStatus(ready = true, deviceName = "CABLE Output (VB-Audio Virtual Cable)")
    } else {
        VirtualMicStatus(
            ready = false,
            deviceName = "VB-CABLE not installed",
            help = "PhoneInput uses the free VB-CABLE driver as its microphone on Windows. Install it once and apps can pick your phone as a mic.",
            helpUrl = "https://vb-audio.com/Cable/",
        )
    }

    /**
     * Exclusive mode keeps the voice out of the Windows mixer, where screen-share audio (Discord
     * Go Live, OBS "desktop audio") would otherwise pick it up and play it to viewers as an echo.
     * Shared mode is the fallback when another app holds the cable or exclusive mode is disabled.
     */
    override fun openOutput(): AudioOutput? {
        if (cableInput == null) return null
        WasapiOutput.openExclusive("CABLE Input", exclude = listOf("16ch"))?.let { return it }
        Log.w("Using shared mode for CABLE Input: screen-share audio may pick up the microphone")
        return JavaSoundOutput.open(cableInput)
    }
}

/**
 * Linux: create a null sink plus a remapped source named "PhoneInput Microphone", then play into
 * the sink with pacat. Modules we load are unloaded on close; existing ones are reused.
 */
class PulseVirtualMic : VirtualMic {
    private val loadedModules = mutableListOf<String>()

    override val status: VirtualMicStatus = try {
        if (pactl("list", "short", "sinks").lines().none { it.split('\t').getOrNull(1) == SINK }) {
            loadedModules += pactl(
                "load-module", "module-null-sink",
                "sink_name=$SINK",
                "sink_properties=\"device.description='PhoneInput Output'\"",
            ).trim()
        }
        if (pactl("list", "short", "sources").lines().none { it.split('\t').getOrNull(1) == SOURCE }) {
            loadedModules += pactl(
                "load-module", "module-remap-source",
                "master=$SINK.monitor",
                "source_name=$SOURCE",
                "source_properties=\"device.description='PhoneInput Microphone'\"",
            ).trim()
        }
        VirtualMicStatus(ready = true, deviceName = "PhoneInput Microphone")
    } catch (e: Exception) {
        VirtualMicStatus(
            ready = false,
            deviceName = "Audio server not found",
            help = "PhoneInput needs PulseAudio or PipeWire (with pipewire-pulse) and the pactl/pacat tools from pulseaudio-utils.",
        )
    }

    override fun openOutput(): AudioOutput? = if (status.ready) PipedOutput.pacat(SINK) else null

    override fun close() {
        loadedModules.asReversed().forEach { id -> runCatching { pactl("unload-module", id) } }
        loadedModules.clear()
    }

    private fun pactl(vararg args: String): String {
        val process = ProcessBuilder("pactl", *args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroy()
            error("pactl ${args.joinToString(" ")} failed: $out")
        }
        return out
    }

    private companion object {
        const val SINK = "phoneinput_sink"
        const val SOURCE = "phoneinput_mic"
    }
}

/**
 * Linux without pulseaudio-utils (stock Ubuntu with PipeWire): a `pw-loopback` process provides
 * the "PhoneInput Output" sink and the "PhoneInput Microphone" source, and pw-cat plays into the
 * sink. Both nodes disappear when the process ends, so nothing is left behind after a crash.
 */
class PipeWireVirtualMic : VirtualMic {
    private val loopback: Process? = runCatching {
        ProcessBuilder(
            "pw-loopback", "--name=phoneinput",
            "--capture-props={ media.class=Audio/Sink node.name=$SINK node.description=\"PhoneInput Output\" audio.position=[MONO] }",
            "--playback-props={ media.class=Audio/Source node.name=$SOURCE node.description=\"PhoneInput Microphone\" audio.position=[MONO] }",
        )
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.getOrNull()

    override val status: VirtualMicStatus = if (loopback != null && !loopback.waitFor(STARTUP_MS, TimeUnit.MILLISECONDS)) {
        VirtualMicStatus(ready = true, deviceName = "PhoneInput Microphone")
    } else {
        Log.w("pw-loopback failed to start (exit ${loopback?.exitValue()})")
        VirtualMicStatus(
            ready = false,
            deviceName = "Audio server not found",
            help = "PhoneInput needs PipeWire (pipewire-bin) or PulseAudio with the pactl/pacat tools from pulseaudio-utils.",
        )
    }

    override fun openOutput(): AudioOutput? = if (status.ready) PipedOutput.pwCat(SINK) else null

    override fun close() {
        loopback?.destroy()
    }

    private companion object {
        const val SINK = "phoneinput_sink"
        const val SOURCE = "phoneinput_mic"
        const val STARTUP_MS = 500L
    }
}

class UnsupportedMic(os: HostOs) : VirtualMic {
    override val status = VirtualMicStatus(
        ready = false,
        deviceName = "Not supported",
        help = "${os.label} isn't supported yet. Turn on “Listen on this computer” to hear the phone.",
    )

    override fun openOutput(): AudioOutput? = null
}
