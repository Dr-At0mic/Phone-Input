package app.phoneinput.desktop.win

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.util.UUID

/** A Windows GUID in native layout: Data1..3 little-endian, Data4 as-is. */
internal fun guidMemory(uuid: String): Memory {
    val u = UUID.fromString(uuid)
    val msb = u.mostSignificantBits
    val lsb = u.leastSignificantBits
    return Memory(16).apply {
        setInt(0, (msb ushr 32).toInt())
        setShort(4, (msb ushr 16).toShort())
        setShort(6, msb.toShort())
        for (i in 0 until 8) setByte(8L + i, (lsb ushr (56 - 8 * i)).toByte())
    }
}

@Suppress("FunctionName")
internal interface Ole32 : StdCallLibrary {
    fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
    fun CoUninitialize()
    fun CoCreateInstance(clsid: Pointer, outer: Pointer?, clsCtx: Int, iid: Pointer, out: PointerByReference): Int
    fun CoTaskMemFree(p: Pointer?)

    companion object {
        val instance: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }
    }
}

/** HRESULT failure carrying the code, so callers can react to specific ones (e.g. device invalidated). */
internal class ComException(val hr: Int, what: String) : IOException("$what failed (HRESULT 0x${hr.toUInt().toString(16)})")

internal fun checkHr(hr: Int, what: String) {
    if (hr < 0) throw ComException(hr, what)
}

/** A raw COM interface pointer; [call] invokes vtable slot [index] (0–2 are IUnknown). */
internal class ComPtr(val ptr: Pointer) {
    fun call(index: Int, vararg args: Any?): Int {
        val vtbl = ptr.getPointer(0)
        val fn = Function.getFunction(vtbl.getPointer(index.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf(ptr, *args))
    }

    fun release() {
        call(2)
    }
}

@Suppress("FunctionName")
internal interface Kernel32Events : StdCallLibrary {
    fun CreateEventW(attributes: Pointer?, manualReset: Boolean, initialState: Boolean, name: Pointer?): Pointer?
    fun WaitForSingleObject(handle: Pointer, millis: Int): Int
    fun CloseHandle(handle: Pointer): Boolean
    fun GetCurrentProcessId(): Int

    companion object {
        val instance: Kernel32Events by lazy { Native.load("kernel32", Kernel32Events::class.java) }
    }
}

/** True when [riid] (a pointer to a GUID) equals [guid]. */
internal fun Pointer.isGuid(guid: Memory): Boolean = getByteArray(0, 16).contentEquals(guid.getByteArray(0, 16))
