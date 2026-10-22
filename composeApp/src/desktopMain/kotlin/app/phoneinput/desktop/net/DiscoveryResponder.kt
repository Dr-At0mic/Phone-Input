package app.phoneinput.desktop.net

import app.phoneinput.desktop.Log
import app.phoneinput.protocol.HostOs
import app.phoneinput.protocol.Packet
import app.phoneinput.protocol.PacketCodec
import app.phoneinput.protocol.PhoneInputProtocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.concurrent.thread

/** Answers phones' broadcast Discover probes so this computer shows up in their list. */
class DiscoveryResponder(
    private val name: String,
    private val os: HostOs,
    private val audioPort: () -> Int,
    private val listenPort: Int = PhoneInputProtocol.DISCOVERY_PORT,
) : AutoCloseable {
    private var socket: DatagramSocket? = null
    private val loggedPeers = HashSet<String>()

    val boundPort: Int get() = socket?.localPort ?: -1

    /** Binds the discovery port exclusively (see StreamServer.bind). */
    fun bind() {
        socket = DatagramSocket(null).apply {
            reuseAddress = false
            broadcast = true
            bind(InetSocketAddress(listenPort))
        }
        Log.i("Discovery bound to UDP $boundPort")
    }

    fun start() {
        if (socket == null) bind()
        val s = socket!!
        thread(name = "pm-discovery", isDaemon = true) {
            val buf = ByteArray(PhoneInputProtocol.MAX_PACKET_BYTES)
            val dp = DatagramPacket(buf, buf.size)
            while (!s.isClosed) {
                try {
                    dp.length = buf.size
                    s.receive(dp)
                    val probe = PacketCodec.decode(buf, dp.length) as? Packet.Discover ?: continue
                    val port = audioPort()
                    if (port !in 1..65535) {
                        Log.w("Discovery from ${dp.socketAddress} ignored: audio server not running")
                        continue
                    }
                    if (loggedPeers.add(dp.address.hostAddress)) Log.i("Discovered by ${dp.socketAddress}")
                    val reply = PacketCodec.encode(Packet.Announce(probe.nonce, port, os, name))
                    s.send(DatagramPacket(reply, reply.size, dp.socketAddress))
                } catch (e: SocketException) {
                    if (s.isClosed) break
                } catch (e: IOException) {
                    Log.w("Discovery error", e)
                } catch (e: RuntimeException) {
                    Log.e("Discovery error", e)
                }
            }
        }
    }

    override fun close() {
        socket?.close()
    }
}

object HostInfo {
    val os: HostOs = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            "win" in it -> HostOs.Windows
            "linux" in it -> HostOs.Linux
            "mac" in it -> HostOs.MacOs
            else -> HostOs.Unknown
        }
    }

    /** Avoids InetAddress.getLocalHost(), which can block on a DNS lookup. */
    fun hostName(): String {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching { java.io.File("/etc/hostname").readText().trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return "This computer"
    }

    /** Adapter names of VMs, WSL, containers and VPN-like tunnels that a phone can't reach. */
    private val virtualAdapter = Regex(
        "vEthernet|Hyper-V|WSL|VirtualBox|VMware|Docker|vboxnet|virbr|veth|br-|docker|Loopback|TAP-|Tailscale|ZeroTier",
        RegexOption.IGNORE_CASE,
    )

    /** LAN IPv4 addresses a phone could type in manually, real adapters only. */
    fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { virtualAdapter.containsMatchIn(it.displayName.orEmpty()) || virtualAdapter.containsMatchIn(it.name.orEmpty()) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: SocketException) {
        emptyList()
    }
}
