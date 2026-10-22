package app.phoneinput.desktop.net

import app.phoneinput.desktop.Log
import app.phoneinput.protocol.HostOs
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

data class UsbDevice(val serial: String, val model: String, val state: State) {
    enum class State { Ready, Unauthorized, Offline }

    /** Wireless-debugging entries look like ip:port or mDNS names; everything else is a cable. */
    val wireless: Boolean get() = ':' in serial || "._adb-tls-connect" in serial
}

data class UsbStatus(
    val adbPath: String? = null,
    val devices: List<UsbDevice> = emptyList(),
    val installing: Boolean = false,
    val error: String? = null,
) {
    val adbFound: Boolean get() = adbPath != null
}

/**
 * USB support: keeps `adb reverse tcp:5825 tcp:5825` in place for every attached Android device,
 * so the phone app can reach this computer at 127.0.0.1:5825 over the cable.
 *
 * Uses an adb that is already installed (PATH, Android SDK) so it shares the running adb server;
 * otherwise it can download Google's platform-tools into PhoneInput's own folder.
 */
class AdbBridge(
    private val port: Int,
    private val os: HostOs,
    private val onStatus: (UsbStatus) -> Unit,
) : AutoCloseable {
    @Volatile private var running = false
    @Volatile private var status = UsbStatus()
    private val reversed = HashSet<String>()

    private val ownToolsDir: File = (System.getenv("LOCALAPPDATA")?.let { File(it, "PhoneInput") }
        ?: File(System.getProperty("user.home"), ".local/share/phoneinput")).resolve("platform-tools")

    fun start() {
        running = true
        thread(name = "pm-adb", isDaemon = true) {
            while (running) {
                try {
                    poll()
                } catch (e: Exception) {
                    Log.w("adb poll failed: ${e.message}")
                }
                Thread.sleep(2_000)
            }
        }
    }

    private fun poll() {
        val adb = locateAdb()
        if (adb == null) {
            publish(status.copy(adbPath = null, devices = emptyList()))
            return
        }
        val out = run(adb, "devices", "-l") ?: run {
            publish(status.copy(adbPath = adb, error = "adb isn't responding"))
            return
        }
        val devices = out.lines().drop(1).mapNotNull { parseDevice(it) }
        val present = devices.map { it.serial }.toSet()
        reversed.retainAll(present)
        for (d in devices) {
            if (d.state != UsbDevice.State.Ready || d.serial in reversed) continue
            if (run(adb, "-s", d.serial, "reverse", "tcp:$port", "tcp:$port") != null) {
                reversed += d.serial
                Log.i("USB ready for ${d.model} (${d.serial})")
            } else {
                Log.w("adb reverse failed for ${d.serial}")
            }
        }
        publish(UsbStatus(adbPath = adb, devices = devices))
    }

    private fun parseDevice(line: String): UsbDevice? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[0].isEmpty()) return null
        val state = when (parts[1]) {
            "device" -> UsbDevice.State.Ready
            "unauthorized" -> UsbDevice.State.Unauthorized
            else -> UsbDevice.State.Offline
        }
        val model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")?.replace('_', ' ') ?: "Android device"
        return UsbDevice(parts[0], model, state)
    }

    /** Downloads Google's Android platform-tools (adb) into PhoneInput's folder. */
    fun installPlatformTools() {
        if (status.installing) return
        publish(status.copy(installing = true, error = null))
        thread(name = "pm-adb-install", isDaemon = true) {
            val error = try {
                download()
                null
            } catch (e: Exception) {
                Log.e("platform-tools download failed", e)
                e.message ?: "Download failed"
            }
            publish(status.copy(installing = false, error = error))
            if (error == null) poll()
        }
    }

    private fun download() {
        val flavor = if (os == HostOs.Windows) "windows" else "linux"
        val url = "https://dl.google.com/android/repository/platform-tools-latest-$flavor.zip"
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (HTTP ${conn.responseCode})")
            val target = ownToolsDir.parentFile.canonicalFile
            target.mkdirs()
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(target, entry.name).canonicalFile
                    if (!out.path.startsWith(target.path + File.separator)) throw IOException("Unexpected file in download: ${entry.name}")
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            File(ownToolsDir, "adb").takeIf { it.isFile }?.setExecutable(true)
            Log.i("platform-tools installed to $ownToolsDir")
        } finally {
            conn.disconnect()
        }
    }

    private fun locateAdb(): String? {
        val exe = if (os == HostOs.Windows) "adb.exe" else "adb"
        val candidates = buildList {
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(File(it, exe)) }
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull(System::getenv).forEach { add(File(it, "platform-tools/$exe")) }
            System.getenv("LOCALAPPDATA")?.let { add(File(it, "Android/Sdk/platform-tools/$exe")) }
            add(File(System.getProperty("user.home"), "Android/Sdk/platform-tools/$exe"))
            add(File(ownToolsDir, exe))
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    private fun run(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val done = p.waitFor(8, TimeUnit.SECONDS)
        if (!done) {
            p.destroy()
            null
        } else {
            val text = p.inputStream.bufferedReader().readText()
            if (p.exitValue() == 0) text else null
        }
    } catch (_: IOException) {
        null
    }

    private fun publish(s: UsbStatus) {
        if (s == status) return
        status = s
        onStatus(s)
    }

    override fun close() {
        running = false
    }
}
