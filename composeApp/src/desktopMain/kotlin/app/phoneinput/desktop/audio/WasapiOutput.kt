package app.phoneinput.desktop.audio

import app.phoneinput.desktop.Log
import app.phoneinput.desktop.win.ComException
import app.phoneinput.desktop.win.ComPtr
import app.phoneinput.desktop.win.Ole32
import app.phoneinput.desktop.win.WinAudio
import app.phoneinput.desktop.win.checkHr
import app.phoneinput.desktop.win.guidMemory
import app.phoneinput.protocol.PhoneInputProtocol
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.Pointer

/**
 * Plays 48 kHz mono 16-bit PCM into a Windows playback device through WASAPI.
 *
 * Exclusive mode is what the virtual microphone uses: shared-mode sound goes through the Windows
 * mixer, where any app may tap it. Discord's screen-share audio (process loopback of "every app
 * except Discord") then picks up the virtual-mic feed and stream viewers hear the user's voice a
 * second time, as an echo. Exclusive streams bypass the mixer, so no loopback capture sees them.
 * Bonus: a 30 ms buffer instead of Java Sound's 60 ms.
 *
 * Shared mode (with the engine converting formats) is used for "Listen on this computer".
 *
 * Must be used from one thread (the audio pump's), which owns the COM objects.
 */
class WasapiOutput private constructor(
    private val device: ComPtr,
    private val client: ComPtr,
    private val render: ComPtr,
    private val bufferFrames: Int,
    private val format: SampleFormat,
) : AudioOutput {
    /** How each frame is laid out in the device buffer. */
    data class SampleFormat(val channels: Int, val containerBits: Int, val validBits: Int, val float: Boolean) {
        val blockAlign: Int get() = channels * containerBits / 8
        override fun toString() = "${channels}ch ${if (float) "float" else "$validBits-bit"}${if (containerBits != validBits) " in $containerBits" else ""}"
    }

    private var started = false
    private val padding = IntByReference()
    private val data = PointerByReference()
    private var bytes = ByteArray(PhoneInputProtocol.FRAME_SAMPLES * format.blockAlign)

    override fun write(pcm: ByteArray, length: Int, blocking: Boolean): Boolean = try {
        val frames = length / 2
        while (true) {
            checkHr(client.call(6, padding), "GetCurrentPadding")
            if (bufferFrames - padding.value >= frames) break
            if (!blocking) return true
            Thread.sleep(1)
        }
        checkHr(render.call(3, frames, data), "IAudioRenderClient.GetBuffer")
        val size = frames * format.blockAlign
        if (bytes.size < size) bytes = ByteArray(size)
        convert(pcm, frames, bytes)
        data.value.write(0, bytes, 0, size)
        checkHr(render.call(4, frames, 0), "IAudioRenderClient.ReleaseBuffer")
        if (!started) {
            checkHr(client.call(10), "IAudioClient.Start")
            started = true
        }
        true
    } catch (e: ComException) {
        Log.w("WASAPI output failed: ${e.message}")
        false
    }

    /** 16-bit mono little-endian → the device's frame layout, the same sample on every channel. */
    private fun convert(pcm: ByteArray, frames: Int, out: ByteArray) {
        val bytesPerSample = format.containerBits / 8
        var o = 0
        for (i in 0 until frames) {
            val v = (pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF) // sign-extended 16-bit
            val word: Int = when {
                format.float -> java.lang.Float.floatToRawIntBits(v / 32768f)
                // Scale to the valid bits, then left-align in the container (e.g. 24-in-32).
                else -> v shl (format.validBits - 16) shl (format.containerBits - format.validBits)
            }
            repeat(format.channels) {
                for (b in 0 until bytesPerSample) out[o++] = (word ushr (8 * b)).toByte() // little-endian
            }
        }
    }

    override fun close() {
        runCatching { client.call(11) }
        render.release()
        client.release()
        device.release()
    }

    companion object {
        /** 30 ms in 100-ns units: three 10 ms frames of headroom. */
        private const val BUFFER_100NS = 300_000L
        private const val AUDCLNT_E_DEVICE_IN_USE = 0x8889000A.toInt()
        private const val AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED = 0x8889000E.toInt()
        private const val SRC_DEFAULT_QUALITY = 0x08000000
        private val KSDATAFORMAT_SUBTYPE_PCM = guidMemory("00000001-0000-0010-8000-00AA00389B71")
        private val KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = guidMemory("00000003-0000-0010-8000-00AA00389B71")

        /** Exclusive-mode candidates, most common first; virtual cables usually want 24-bit stereo. */
        private val EXCLUSIVE_FORMATS = listOf(
            SampleFormat(2, 24, 24, false),
            SampleFormat(2, 16, 16, false),
            SampleFormat(2, 32, 24, false),
            SampleFormat(2, 32, 32, false),
            SampleFormat(2, 32, 32, true),
            SampleFormat(1, 24, 24, false),
            SampleFormat(1, 16, 16, false),
        )

        /**
         * Opens the first playback device named like [name] (but none of [exclude]) exclusively,
         * or returns null (reason logged) so the caller can fall back to shared mode.
         */
        fun openExclusive(name: String, exclude: List<String> = emptyList()): WasapiOutput? {
            val (device, deviceName) = findDevice(name, exclude) ?: return null
            try {
                for (format in EXCLUSIVE_FORMATS) {
                    val wfx = extensible(format)
                    val probe = WinAudio.activateClient(device)
                    if (probe.call(7, WinAudio.AUDCLNT_SHAREMODE_EXCLUSIVE, wfx, null) != 0) { // IsFormatSupported
                        probe.release()
                        continue
                    }
                    var client = probe
                    var hr = client.call(3, WinAudio.AUDCLNT_SHAREMODE_EXCLUSIVE, 0, BUFFER_100NS, 0L, wfx, null)
                    if (hr == WinAudio.AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
                        // Retry with the buffer size the driver wants (documented WASAPI dance).
                        val frames = IntByReference()
                        checkHr(client.call(4, frames), "GetBufferSize")
                        val aligned = (10_000_000.0 * frames.value / PhoneInputProtocol.SAMPLE_RATE + 0.5).toLong()
                        client.release()
                        client = WinAudio.activateClient(device)
                        hr = client.call(3, WinAudio.AUDCLNT_SHAREMODE_EXCLUSIVE, 0, aligned, 0L, wfx, null)
                    }
                    if (hr < 0) {
                        client.release()
                        val why = when (hr) {
                            AUDCLNT_E_DEVICE_IN_USE -> "another app is using it"
                            AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED -> "exclusive mode is turned off in its Windows sound settings"
                            else -> "HRESULT 0x${hr.toUInt().toString(16)}"
                        }
                        Log.w("Can't open $deviceName exclusively: $why")
                        device.release()
                        return null
                    }
                    return finish(device, client, format, "$deviceName, exclusive")
                }
                Log.w("$deviceName accepts none of PhoneInput's exclusive-mode formats")
            } catch (e: Exception) {
                Log.w("Exclusive output unavailable: ${e.message}")
            }
            device.release()
            return null
        }

        /** Shared mode on the device named like [name], or the default playback device when null. */
        fun openShared(name: String? = null): WasapiOutput? {
            val (device, deviceName) = (if (name == null) defaultDevice() else findDevice(name, emptyList())) ?: return null
            return try {
                val format = SampleFormat(2, 16, 16, false)
                val client = WinAudio.activateClient(device)
                val flags = WinAudio.AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM or SRC_DEFAULT_QUALITY
                val hr = client.call(3, WinAudio.AUDCLNT_SHAREMODE_SHARED, flags, BUFFER_100NS * 2, 0L, WinAudio.pcm16Format(PhoneInputProtocol.SAMPLE_RATE, 2), null)
                if (hr < 0) {
                    client.release()
                    throw ComException(hr, "IAudioClient.Initialize (shared)")
                }
                finish(device, client, format, "$deviceName, shared")
            } catch (e: Exception) {
                Log.w("Shared WASAPI output unavailable: ${e.message}")
                device.release()
                null
            }
        }

        private fun finish(device: ComPtr, client: ComPtr, format: SampleFormat, what: String): WasapiOutput {
            val bufferFrames = IntByReference()
            checkHr(client.call(4, bufferFrames), "GetBufferSize")
            val renderRef = PointerByReference()
            checkHr(client.call(14, WinAudio.IID_IAudioRenderClient, renderRef), "GetService(IAudioRenderClient)")
            Log.i("Audio output: $what, $format, ${bufferFrames.value * 1000 / PhoneInputProtocol.SAMPLE_RATE} ms buffer")
            return WasapiOutput(device, client, ComPtr(renderRef.value), bufferFrames.value, format)
        }

        private fun findDevice(name: String, exclude: List<String>): Pair<ComPtr, String>? {
            if (Native.POINTER_SIZE != 8) return null
            val ole = Ole32.instance
            ole.CoInitializeEx(null, 0) // MTA; harmless if this thread is already initialised
            return try {
                WinAudio.findRenderDevice(ole, name, exclude)
            } catch (e: Exception) {
                Log.w("Couldn't look up $name: ${e.message}")
                null
            }
        }

        private fun defaultDevice(): Pair<ComPtr, String>? {
            if (Native.POINTER_SIZE != 8) return null
            val ole = Ole32.instance
            ole.CoInitializeEx(null, 0)
            return try {
                val enumerator = WinAudio.enumerator(ole)
                try {
                    val ref = PointerByReference()
                    checkHr(enumerator.call(4, WinAudio.E_RENDER, 0, ref), "GetDefaultAudioEndpoint")
                    ComPtr(ref.value) to "default output"
                } finally {
                    enumerator.release()
                }
            } catch (e: Exception) {
                Log.w("No default output: ${e.message}")
                null
            }
        }

        /** WAVEFORMATEXTENSIBLE (40 bytes) for [f] at 48 kHz. */
        private fun extensible(f: SampleFormat): Pointer = Memory(40).apply {
            clear()
            setShort(0, 0xFFFE.toShort())
            setShort(2, f.channels.toShort())
            setInt(4, PhoneInputProtocol.SAMPLE_RATE)
            setInt(8, PhoneInputProtocol.SAMPLE_RATE * f.blockAlign)
            setShort(12, f.blockAlign.toShort())
            setShort(14, f.containerBits.toShort())
            setShort(16, 22)
            setShort(18, f.validBits.toShort())
            setInt(20, if (f.channels == 2) 0x3 else 0x4) // FL|FR, or FC for mono
            write(24, (if (f.float) KSDATAFORMAT_SUBTYPE_IEEE_FLOAT else KSDATAFORMAT_SUBTYPE_PCM).getByteArray(0, 16), 0, 16)
        }
    }
}
