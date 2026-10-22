package app.phoneinput.desktop.audio

import app.phoneinput.desktop.Log
import app.phoneinput.desktop.win.ComException
import app.phoneinput.desktop.win.ComPtr
import app.phoneinput.desktop.win.Kernel32Events
import app.phoneinput.desktop.win.Ole32
import app.phoneinput.desktop.win.WinAudio
import app.phoneinput.desktop.win.checkHr
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Windows: captures what the computer plays, through WASAPI (raw COM via JNA).
 *
 * Preferred: process loopback (Windows 10 2004+) of every process *except PhoneInput*. That keeps
 * PhoneInput's own sound out: "Listen on this computer" and the virtual-microphone feed must never
 * travel back to the phone, or the user hears their own voice from it.
 *
 * Fallback on older Windows: loopback of the default playback device, reopened when the user
 * switches devices. That one can't exclude PhoneInput, so it is only used when it has to be.
 */
internal class WasapiLoopback(
    private val onFrame: (ShortArray) -> Unit,
    private val onError: (String) -> Unit,
    /** Process whose audio is left out; PhoneInput itself unless a test says otherwise. */
    private val excludePid: Int = Kernel32Events.instance.GetCurrentProcessId(),
) : SystemAudioCapture {
    @Volatile private var running = true
    private val worker: Thread

    init {
        if (Native.POINTER_SIZE != 8) throw IOException("Needs 64-bit Windows")
        worker = thread(name = "pm-loopback", isDaemon = true, priority = Thread.MAX_PRIORITY) { run() }
    }

    override fun close() {
        running = false
        if (Thread.currentThread() !== worker) worker.join(1_000) // close() may come from our own onError
    }

    private fun run() {
        val ole = Ole32.instance
        ole.CoInitializeEx(null, COINIT_MULTITHREADED)
        var failures = 0
        var processLoopback = true
        try {
            while (running) {
                try {
                    if (processLoopback) {
                        val client = try {
                            WinAudio.processLoopbackClient(excludePid)
                        } catch (e: Exception) {
                            Log.w("Process loopback unavailable (${e.message}); capturing the default output device instead")
                            processLoopback = false
                            continue
                        }
                        captureProcessLoopback(client)
                    } else {
                        captureDefaultDevice(ole)
                    }
                    failures = 0
                } catch (e: ComException) {
                    // Device removed or switched mid-stream: reopen.
                    if (e.hr != WinAudio.AUDCLNT_E_DEVICE_INVALIDATED) failures++
                    Log.w("Loopback capture: ${e.message}")
                    if (failures >= 3) {
                        onError("Couldn't capture this computer's audio")
                        return
                    }
                    Thread.sleep(500)
                }
            }
        } catch (e: Throwable) {
            Log.e("Loopback capture failed", e)
            if (running) onError("Couldn't capture this computer's audio")
        } finally {
            ole.CoUninitialize()
        }
    }

    /** Every process but PhoneInput, on all output devices. Event-driven, as process loopback requires. */
    private fun captureProcessLoopback(client: ComPtr) {
        val k32 = Kernel32Events.instance
        val event = k32.CreateEventW(null, false, false, null) ?: throw IOException("CreateEvent failed")
        var capture: ComPtr? = null
        try {
            val format = WinAudio.pcm16Format(SAMPLE_RATE, CHANNELS)
            val flags = WinAudio.AUDCLNT_STREAMFLAGS_LOOPBACK or WinAudio.AUDCLNT_STREAMFLAGS_EVENTCALLBACK or WinAudio.AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
            checkHr(client.call(3, WinAudio.AUDCLNT_SHAREMODE_SHARED, flags, BUFFER_100NS, 0L, format, null), "IAudioClient.Initialize (process loopback)")
            checkHr(client.call(13, event), "SetEventHandle")
            val capRef = PointerByReference()
            checkHr(client.call(14, WinAudio.IID_IAudioCaptureClient, capRef), "GetService(IAudioCaptureClient)")
            capture = ComPtr(capRef.value)
            checkHr(client.call(10), "IAudioClient.Start")
            Log.i("Capturing computer audio (all apps except PhoneInput)")
            val drain = Drainer(capture, CHANNELS, isFloat = false, FrameAssembler(SAMPLE_RATE, CHANNELS, onFrame))
            while (running) {
                k32.WaitForSingleObject(event, 100)
                drain.all()
            }
            client.call(11) // Stop
        } finally {
            capture?.release()
            client.release()
            k32.CloseHandle(event)
        }
    }

    /** Fallback: the default output device's loopback. Returns when the default device changes. */
    private fun captureDefaultDevice(ole: Ole32) {
        val opened = mutableListOf<ComPtr>()
        var format: Pointer? = null
        try {
            val enumerator = WinAudio.enumerator(ole).also(opened::add)
            val device = defaultDevice(enumerator).also(opened::add)
            val deviceId = deviceId(ole, device)
            val client = WinAudio.activateClient(device).also(opened::add)

            val fmtRef = PointerByReference()
            checkHr(client.call(8, fmtRef), "GetMixFormat")
            val fmt = fmtRef.value.also { format = it }
            val tag = fmt.getShort(0).toInt() and 0xFFFF
            val channels = fmt.getShort(2).toInt()
            val rate = fmt.getInt(4)
            val bits = fmt.getShort(14).toInt()
            val subFormat = if (tag == WAVE_FORMAT_EXTENSIBLE) fmt.getInt(24) else tag
            val isFloat = subFormat == WAVE_FORMAT_IEEE_FLOAT && bits == 32
            val isPcm16 = subFormat == WAVE_FORMAT_PCM && bits == 16
            if (!isFloat && !isPcm16) throw IOException("Unsupported output format: $bits-bit, tag $subFormat")

            checkHr(client.call(3, WinAudio.AUDCLNT_SHAREMODE_SHARED, WinAudio.AUDCLNT_STREAMFLAGS_LOOPBACK, BUFFER_100NS, 0L, fmt, null), "IAudioClient.Initialize")
            val capRef = PointerByReference()
            checkHr(client.call(14, WinAudio.IID_IAudioCaptureClient, capRef), "GetService(IAudioCaptureClient)")
            val capture = ComPtr(capRef.value).also(opened::add)
            checkHr(client.call(10), "IAudioClient.Start")
            Log.i("Capturing computer audio (default device): $rate Hz, $channels ch, ${if (isFloat) "float" else "16-bit"}")

            val drain = Drainer(capture, channels, isFloat, FrameAssembler(rate, channels, onFrame))
            var lastDeviceCheck = System.currentTimeMillis()
            while (running) {
                Thread.sleep(POLL_MS)
                drain.all()
                val now = System.currentTimeMillis()
                if (now - lastDeviceCheck >= DEVICE_CHECK_MS) {
                    lastDeviceCheck = now
                    val current = defaultDevice(enumerator)
                    val changed = try {
                        deviceId(ole, current) != deviceId
                    } finally {
                        current.release()
                    }
                    if (changed) {
                        Log.i("Default output device changed; following it")
                        return
                    }
                }
            }
            client.call(11) // Stop
        } finally {
            opened.asReversed().forEach { runCatching { it.release() } }
            format?.let(ole::CoTaskMemFree)
        }
    }

    /** Reads every packet the capture client has ready and feeds it to the assembler. */
    private class Drainer(private val capture: ComPtr, private val channels: Int, private val isFloat: Boolean, private val assembler: FrameAssembler) {
        private var floats = FloatArray(4_800 * channels)
        private val packetSize = IntByReference()
        private val data = PointerByReference()
        private val frames = IntByReference()
        private val flags = IntByReference()

        fun all() {
            checkHr(capture.call(5, packetSize), "GetNextPacketSize")
            while (packetSize.value > 0) {
                checkHr(capture.call(3, data, frames, flags, null, null), "GetBuffer")
                val n = frames.value
                val count = n * channels
                if (floats.size < count) floats = FloatArray(count)
                if (flags.value and AUDCLNT_BUFFERFLAGS_SILENT != 0) {
                    floats.fill(0f, 0, count)
                } else if (isFloat) {
                    data.value.read(0, floats, 0, count)
                } else {
                    val shorts = data.value.getShortArray(0, count)
                    for (i in 0 until count) floats[i] = shorts[i] / 32768f
                }
                checkHr(capture.call(4, n), "ReleaseBuffer")
                assembler.push(floats, n)
                checkHr(capture.call(5, packetSize), "GetNextPacketSize")
            }
        }
    }

    private fun defaultDevice(enumerator: ComPtr): ComPtr {
        val devRef = PointerByReference()
        checkHr(enumerator.call(4, WinAudio.E_RENDER, E_CONSOLE, devRef), "GetDefaultAudioEndpoint")
        return ComPtr(devRef.value)
    }

    private fun deviceId(ole: Ole32, device: ComPtr): String {
        val idRef = PointerByReference()
        checkHr(device.call(5, idRef), "IMMDevice.GetId")
        return try {
            idRef.value.getWideString(0)
        } finally {
            ole.CoTaskMemFree(idRef.value)
        }
    }

    private companion object {
        const val COINIT_MULTITHREADED = 0
        const val E_CONSOLE = 0
        const val AUDCLNT_BUFFERFLAGS_SILENT = 0x2
        const val WAVE_FORMAT_PCM = 1
        const val WAVE_FORMAT_IEEE_FLOAT = 3
        const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2

        /** Shared-mode buffer: 100 ms in 100-ns units. */
        const val BUFFER_100NS = 1_000_000L
        const val POLL_MS = 5L
        const val DEVICE_CHECK_MS = 2_000L
    }
}
