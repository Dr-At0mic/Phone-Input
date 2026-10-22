package app.phoneinput.desktop.win

import app.phoneinput.desktop.Log
import app.phoneinput.desktop.net.HostInfo
import app.phoneinput.desktop.update.DesktopInstaller
import app.phoneinput.protocol.HostOs
import java.util.concurrent.TimeUnit

/**
 * "Start with Windows": a per-user `HKCU\...\Run` entry that starts the installed PhoneInput.exe
 * hidden in the tray ([TRAY_ARG]) at sign-in. Only for installed builds, never under `gradle run`.
 */
object Autostart {
    const val TRAY_ARG = "--tray"
    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val VALUE = "PhoneInput"

    private val exe: String? = ProcessHandle.current().info().command().orElse(null)

    val supported: Boolean = HostInfo.os == HostOs.Windows && DesktopInstaller.currentVersion != null && exe != null

    fun isEnabled(): Boolean = supported && reg("query", RUN_KEY, "/v", VALUE)

    fun setEnabled(on: Boolean): Boolean {
        if (!supported) return false
        val ok = if (on) {
            reg("add", RUN_KEY, "/v", VALUE, "/t", "REG_SZ", "/d", "\"$exe\" $TRAY_ARG", "/f")
        } else {
            reg("delete", RUN_KEY, "/v", VALUE, "/f")
        }
        if (!ok) Log.w("Couldn't ${if (on) "enable" else "disable"} Start with Windows")
        return ok
    }

    private fun reg(vararg args: String): Boolean = runCatching {
        val p = ProcessBuilder(listOf("reg") + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)
}
