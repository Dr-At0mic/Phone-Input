package app.phoneinput.desktop.net.bluetooth

import app.phoneinput.desktop.Log
import app.phoneinput.desktop.win.guidMemory
import app.phoneinput.protocol.PhoneInputProtocol
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException

/**
 * RFCOMM through Winsock's Bluetooth provider (AF_BTH): bind to any free channel, then publish an
 * SDP record for [PhoneInputProtocol.BLUETOOTH_SERVICE_UUID] with WSASetService so the phone can
 * find the channel by UUID.
 */
internal object WindowsRfcomm {
    private const val AF_BTH = 32
    private const val SOCK_STREAM = 1
    private const val BTHPROTO_RFCOMM = 3
    private const val NS_BTH = 16
    private const val RNRSERVICE_REGISTER = 0
    private const val RNRSERVICE_DELETE = 2
    private const val SD_BOTH = 2
    private const val INVALID_SOCKET = -1L

    /** SOCKADDR_BTH is packed: family u16 @0, btAddr u64 @2, serviceClassId GUID @10, port u32 @26. */
    private const val SOCKADDR_BTH_SIZE = 30
    private const val PORT_OFFSET = 26L
    private const val BT_PORT_ANY = -1

    private const val WSAEAFNOSUPPORT = 10047
    private const val WSAENETDOWN = 10050
    private const val WSAEADDRNOTAVAIL = 10049

    @Suppress("FunctionName", "LocalVariableName")
    private interface Ws2 : StdCallLibrary {
        fun WSAStartup(version: Short, data: Pointer): Int
        fun WSAGetLastError(): Int
        fun socket(af: Int, type: Int, protocol: Int): Long
        fun bind(s: Long, addr: Pointer, len: Int): Int
        fun listen(s: Long, backlog: Int): Int
        fun getsockname(s: Long, addr: Pointer, len: IntByReference): Int
        fun accept(s: Long, addr: Pointer?, len: IntByReference?): Long
        fun recv(s: Long, buf: ByteArray, len: Int, flags: Int): Int
        fun send(s: Long, buf: ByteArray, len: Int, flags: Int): Int
        fun shutdown(s: Long, how: Int): Int
        fun closesocket(s: Long): Int
        fun WSASetServiceW(querySet: WsaQuerySet, op: Int, flags: Int): Int
    }

    @Suppress("unused")
    @Structure.FieldOrder("lpSockaddr", "iSockaddrLength")
    open class SocketAddress : Structure() {
        @JvmField var lpSockaddr: Pointer? = null
        @JvmField var iSockaddrLength: Int = 0

        class ByValue : SocketAddress(), Structure.ByValue
    }

    @Suppress("unused")
    @Structure.FieldOrder("localAddr", "remoteAddr", "iSocketType", "iProtocol")
    class CsAddrInfo : Structure() {
        @JvmField var localAddr = SocketAddress.ByValue()
        @JvmField var remoteAddr = SocketAddress.ByValue()
        @JvmField var iSocketType: Int = 0
        @JvmField var iProtocol: Int = 0
    }

    @Suppress("unused")
    @Structure.FieldOrder(
        "dwSize", "lpszServiceInstanceName", "lpServiceClassId", "lpVersion", "lpszComment", "dwNameSpace",
        "lpNSProviderId", "lpszContext", "dwNumberOfProtocols", "lpafpProtocols", "lpszQueryString",
        "dwNumberOfCsAddrs", "lpcsaBuffer", "dwOutputFlags", "lpBlob",
    )
    class WsaQuerySet : Structure() {
        @JvmField var dwSize: Int = 0
        @JvmField var lpszServiceInstanceName: WString? = null
        @JvmField var lpServiceClassId: Pointer? = null
        @JvmField var lpVersion: Pointer? = null
        @JvmField var lpszComment: WString? = null
        @JvmField var dwNameSpace: Int = 0
        @JvmField var lpNSProviderId: Pointer? = null
        @JvmField var lpszContext: WString? = null
        @JvmField var dwNumberOfProtocols: Int = 0
        @JvmField var lpafpProtocols: Pointer? = null
        @JvmField var lpszQueryString: WString? = null
        @JvmField var dwNumberOfCsAddrs: Int = 0
        @JvmField var lpcsaBuffer: Pointer? = null
        @JvmField var dwOutputFlags: Int = 0
        @JvmField var lpBlob: Pointer? = null
    }

    private val ws2: Ws2 by lazy {
        if (Native.POINTER_SIZE != 8) throw BluetoothUnavailableException("Bluetooth needs 64-bit Windows.")
        Native.load("ws2_32", Ws2::class.java).also { it.WSAStartup(0x0202, Memory(1024)) }
    }

    fun listen(serviceName: String): RfcommServer {
        val api = ws2
        val s = api.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (s == INVALID_SOCKET) throw unavailable("create a Bluetooth socket", api.WSAGetLastError())
        try {
            val addr = Memory(SOCKADDR_BTH_SIZE.toLong()).apply {
                clear()
                setShort(0, AF_BTH.toShort())
                setInt(PORT_OFFSET, BT_PORT_ANY)
            }
            if (api.bind(s, addr, SOCKADDR_BTH_SIZE) != 0) throw unavailable("bind the Bluetooth socket", api.WSAGetLastError())
            if (api.listen(s, 4) != 0) throw unavailable("listen on Bluetooth", api.WSAGetLastError())
            val len = IntByReference(SOCKADDR_BTH_SIZE)
            if (api.getsockname(s, addr, len) != 0) throw unavailable("read the Bluetooth channel", api.WSAGetLastError())
            val channel = addr.getInt(PORT_OFFSET)

            val record = serviceRecord(serviceName, addr)
            if (api.WSASetServiceW(record.query, RNRSERVICE_REGISTER, 0) != 0) {
                throw unavailable("advertise the PhoneInput service", api.WSAGetLastError())
            }
            Log.i("Bluetooth: listening on RFCOMM channel $channel, SDP record published")
            return Server(api, s, channel, record)
        } catch (e: Throwable) {
            api.closesocket(s)
            throw e
        }
    }

    /** The query set and everything it points to, kept reachable for as long as the record is published. */
    private class ServiceRecord(val query: WsaQuerySet, val keepAlive: List<Any>)

    private fun serviceRecord(name: String, sockaddr: Memory): ServiceRecord {
        val guid = guidMemory(PhoneInputProtocol.BLUETOOTH_SERVICE_UUID)
        val csAddr = CsAddrInfo().apply {
            localAddr.lpSockaddr = sockaddr
            localAddr.iSockaddrLength = SOCKADDR_BTH_SIZE
            iSocketType = SOCK_STREAM
            iProtocol = BTHPROTO_RFCOMM
            write()
        }
        val query = WsaQuerySet().apply {
            dwSize = size()
            lpszServiceInstanceName = WString(name)
            lpszComment = WString("PhoneInput microphone stream")
            lpServiceClassId = guid
            dwNameSpace = NS_BTH
            dwNumberOfCsAddrs = 1
            lpcsaBuffer = csAddr.pointer
        }
        return ServiceRecord(query, listOf(guid, csAddr, sockaddr))
    }

    private fun unavailable(action: String, code: Int): IOException = when (code) {
        WSAEAFNOSUPPORT, WSAENETDOWN, WSAEADDRNOTAVAIL ->
            BluetoothUnavailableException("Bluetooth is off or this computer has no Bluetooth adapter.")
        else -> IOException("Couldn't $action (Winsock error $code).")
    }

    private class Server(private val api: Ws2, private val s: Long, override val channel: Int, private val record: ServiceRecord) : RfcommServer {
        @Volatile private var closed = false

        override fun accept(): RfcommConnection {
            val c = api.accept(s, null, null)
            if (c == INVALID_SOCKET) {
                val code = api.WSAGetLastError()
                throw IOException(if (closed) "Bluetooth server closed" else "Bluetooth accept failed (Winsock error $code)")
            }
            return RfcommConnection(
                input = NativeInputStream { buf, len ->
                    val n = api.recv(c, buf, len, 0)
                    if (n < 0) throw IOException("Bluetooth receive failed (Winsock error ${api.WSAGetLastError()})")
                    n
                },
                output = NativeOutputStream { buf, len ->
                    val n = api.send(c, buf, len, 0)
                    if (n < 0) throw IOException("Bluetooth send failed (Winsock error ${api.WSAGetLastError()})")
                    n
                },
                onClose = {
                    api.shutdown(c, SD_BOTH)
                    api.closesocket(c)
                },
            )
        }

        override fun close() {
            if (closed) return
            closed = true
            api.WSASetServiceW(record.query, RNRSERVICE_DELETE, 0)
            api.closesocket(s)
        }
    }
}
