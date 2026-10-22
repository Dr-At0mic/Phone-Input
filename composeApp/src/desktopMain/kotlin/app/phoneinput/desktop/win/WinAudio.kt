package app.phoneinput.desktop.win

import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Core Audio (WASAPI) plumbing shared by the loopback capture and the exclusive-mode output. */
internal object WinAudio {
    val CLSID_MMDeviceEnumerator = guidMemory("BCDE0395-E52F-467C-8E3D-C4579291692E")
    val IID_IMMDeviceEnumerator = guidMemory("A95664D2-9614-4F35-A746-DE8DB63617E6")
    val IID_IAudioClient = guidMemory("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2")
    val IID_IAudioCaptureClient = guidMemory("C8ADBD64-E71E-48A0-A4DE-185C395CD317")
    val IID_IAudioRenderClient = guidMemory("F294ACFC-3146-4483-A7BF-ADDCA7C260E2")
    private val IID_IUnknown = guidMemory("00000000-0000-0000-C000-000000000046")
    private val IID_IAgileObject = guidMemory("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90")
    private val IID_IActivateAudioInterfaceCompletionHandler = guidMemory("41D949AB-9862-444A-80F6-C261334DA5EB")
    private val PKEY_Device_FriendlyName = guidMemory("A45C254E-DF1C-4EFD-8020-67D146A850E0")

    const val CLSCTX_ALL = 0x17
    const val E_RENDER = 0
    const val DEVICE_STATE_ACTIVE = 1
    const val AUDCLNT_SHAREMODE_SHARED = 0
    const val AUDCLNT_SHAREMODE_EXCLUSIVE = 1
    const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
    const val AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000
    const val AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000.toInt()
    const val AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004.toInt()
    const val AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED = 0x88890019.toInt()
    private const val E_NOINTERFACE = 0x80004002.toInt()
    private const val VT_BLOB: Short = 65
    private const val VT_LPWSTR: Short = 31

    fun enumerator(ole: Ole32): ComPtr {
        val ref = PointerByReference()
        checkHr(ole.CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, ref), "MMDeviceEnumerator")
        return ComPtr(ref.value)
    }

    /** The first active playback device whose name contains [nameContains] and none of [exclude]; caller releases it. */
    fun findRenderDevice(ole: Ole32, nameContains: String, exclude: List<String> = emptyList()): Pair<ComPtr, String>? {
        val enumerator = enumerator(ole)
        try {
            val collRef = PointerByReference()
            checkHr(enumerator.call(3, E_RENDER, DEVICE_STATE_ACTIVE, collRef), "EnumAudioEndpoints")
            val collection = ComPtr(collRef.value)
            try {
                val count = IntByReference()
                checkHr(collection.call(3, count), "IMMDeviceCollection.GetCount")
                for (i in 0 until count.value) {
                    val devRef = PointerByReference()
                    checkHr(collection.call(4, i, devRef), "IMMDeviceCollection.Item")
                    val device = ComPtr(devRef.value)
                    val name = runCatching { friendlyName(ole, device) }.getOrNull()
                    if (name != null && name.contains(nameContains, ignoreCase = true) && exclude.none { name.contains(it, ignoreCase = true) }) {
                        return device to name
                    }
                    device.release()
                }
                return null
            } finally {
                collection.release()
            }
        } finally {
            enumerator.release()
        }
    }

    private fun friendlyName(ole: Ole32, device: ComPtr): String? {
        val storeRef = PointerByReference()
        checkHr(device.call(4, 0 /* STGM_READ */, storeRef), "OpenPropertyStore")
        val store = ComPtr(storeRef.value)
        try {
            val key = Memory(20).apply {
                write(0, PKEY_Device_FriendlyName.getByteArray(0, 16), 0, 16)
                setInt(16, 14)
            }
            val value = Memory(24).apply { clear() }
            checkHr(store.call(5, key, value), "IPropertyStore.GetValue")
            return if (value.getShort(0) == VT_LPWSTR) {
                val str = value.getPointer(8)
                str.getWideString(0).also { ole.CoTaskMemFree(str) }
            } else {
                null
            }
        } finally {
            store.release()
        }
    }

    fun activateClient(device: ComPtr): ComPtr {
        val ref = PointerByReference()
        checkHr(device.call(3, IID_IAudioClient, CLSCTX_ALL, null, ref), "IMMDevice.Activate")
        return ComPtr(ref.value)
    }

    /** A plain WAVEFORMATEX for 16-bit PCM. */
    fun pcm16Format(rate: Int, channels: Int): Memory = Memory(18).apply {
        clear()
        setShort(0, 1) // WAVE_FORMAT_PCM
        setShort(2, channels.toShort())
        setInt(4, rate)
        setInt(8, rate * channels * 2)
        setShort(12, (channels * 2).toShort())
        setShort(14, 16)
    }

    // --- Process loopback (Windows 10 2004+) -------------------------------------------------

    @Suppress("FunctionName")
    private interface Mmdevapi : StdCallLibrary {
        fun ActivateAudioInterfaceAsync(path: WString, riid: Pointer, params: Pointer?, handler: Pointer, op: PointerByReference): Int
    }

    private interface QueryInterfaceFn : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer, riid: Pointer, ppv: Pointer): Int
    }

    private interface RefCountFn : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer): Int
    }

    private interface ActivateCompletedFn : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer, operation: Pointer): Int
    }

    /** Completion handlers handed to Windows (a few hundred bytes per capture start). */
    private val retained = mutableListOf<Any>()

    private val mmdevapi: Mmdevapi by lazy { Native.load("mmdevapi", Mmdevapi::class.java) }

    /**
     * An IAudioClient that captures what every process plays except [excludePid] and its children,
     * on any output device. Throws on Windows versions without process loopback (before 10 2004).
     */
    fun processLoopbackClient(excludePid: Int): ComPtr {
        val done = CountDownLatch(1)
        var activateHr = 0
        var activated: Pointer? = null

        // A minimal COM object implementing IActivateAudioInterfaceCompletionHandler (+ IAgileObject).
        val queryInterface = object : QueryInterfaceFn {
            override fun invoke(self: Pointer, riid: Pointer, ppv: Pointer): Int =
                if (riid.isGuid(IID_IUnknown) || riid.isGuid(IID_IAgileObject) || riid.isGuid(IID_IActivateAudioInterfaceCompletionHandler)) {
                    ppv.setPointer(0, self)
                    0
                } else {
                    ppv.setPointer(0, Pointer.NULL)
                    E_NOINTERFACE
                }
        }
        val addRef = object : RefCountFn {
            override fun invoke(self: Pointer) = 1
        }
        val release = object : RefCountFn {
            override fun invoke(self: Pointer) = 1
        }
        val completed = object : ActivateCompletedFn {
            override fun invoke(self: Pointer, operation: Pointer): Int {
                val hr = IntByReference()
                val unknown = PointerByReference()
                val callHr = ComPtr(operation).call(3, hr, unknown) // GetActivateResult
                activateHr = if (callHr < 0) callHr else hr.value
                activated = unknown.value
                done.countDown()
                return 0
            }
        }
        val vtable = Memory(4L * Native.POINTER_SIZE).apply {
            setPointer(0, CallbackReference.getFunctionPointer(queryInterface))
            setPointer(1L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef))
            setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release))
            setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(completed))
        }
        val handler = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }

        // AUDIOCLIENT_ACTIVATION_PARAMS { PROCESS_LOOPBACK, { pid, EXCLUDE_TARGET_PROCESS_TREE } } wrapped in a VT_BLOB PROPVARIANT.
        val params = Memory(12).apply {
            setInt(0, 1)
            setInt(4, excludePid)
            setInt(8, 1)
        }
        val propVariant = Memory(24).apply {
            clear()
            setShort(0, VT_BLOB)
            setInt(8, 12)
            setPointer(16, params)
        }

        val op = PointerByReference()
        checkHr(mmdevapi.ActivateAudioInterfaceAsync(WString("VAD\\Process_Loopback"), IID_IAudioClient, propVariant, handler, op), "ActivateAudioInterfaceAsync")
        try {
            if (!done.await(5, TimeUnit.SECONDS)) throw IOException("Process loopback activation timed out")
        } finally {
            op.value?.let { ComPtr(it).release() }
        }
        checkHr(activateHr, "Process loopback activation")
        // Windows may still call Release on the handler later: its callbacks must never be collected.
        synchronized(retained) { retained.addAll(listOf(queryInterface, addRef, release, completed, vtable, handler, params, propVariant)) }
        return ComPtr(activated ?: throw IOException("Process loopback returned no client"))
    }
}
