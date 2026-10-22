package app.phoneinput.desktop.update

import app.phoneinput.desktop.Log
import app.phoneinput.desktop.net.HostInfo
import app.phoneinput.protocol.HostOs
import app.phoneinput.update.AppVersion
import app.phoneinput.update.Release
import app.phoneinput.update.ReleaseAsset
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * How this copy of PhoneInput was installed, and how to replace it with a newer one: the MSI on
 * Windows, the .deb or .rpm on Linux. A detached script waits for PhoneInput to quit, runs the
 * installer (Windows asks through UAC, Linux through polkit) and starts the new version.
 */
object DesktopInstaller {
    /** Set by the jpackage launcher of installed builds; absent under `gradle run`, which disables updates. */
    val currentVersion: AppVersion? = AppVersion.parse(System.getProperty("jpackage.app-version"))

    private enum class Kind { Msi, Deb, Rpm }

    private val kind: Kind? by lazy {
        val x64 = System.getProperty("os.arch").orEmpty().lowercase() in setOf("amd64", "x86_64")
        when (HostInfo.os) {
            HostOs.Windows -> Kind.Msi
            HostOs.Linux -> when {
                !x64 -> null
                output("dpkg-query", "-W", "-f=\${db:Status-Status}", "phoneinput") == "installed" -> Kind.Deb
                output("rpm", "-q", "phoneinput")?.startsWith("phoneinput-") == true -> Kind.Rpm
                else -> null
            }
            else -> null
        }
    }

    /** The release file matching this installation; null means the user downloads it from the page. */
    fun pickAsset(release: Release): ReleaseAsset? {
        val v = release.version
        return when (kind) {
            Kind.Msi -> release.asset("PhoneInput-$v.msi")
            Kind.Deb -> release.asset("phoneinput_${v}_amd64.deb")
            Kind.Rpm -> release.asset("phoneinput-$v.x86_64.rpm")
            null -> null
        }
    }

    /** Where updates are downloaded: per user, since /tmp on Linux is shared. */
    val downloadDir: File = when (HostInfo.os) {
        HostOs.Linux -> File(System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"), "phoneinput/update")
        else -> File(System.getProperty("java.io.tmpdir"), "PhoneInput/update")
    }

    /** Starts the installer script; the caller must quit right after so files can be replaced. */
    fun launch(file: File) {
        val app = ProcessHandle.current().info().command().orElse(null) ?: throw IOException("Couldn't find PhoneInput's program file.")
        val dir = file.parentFile
        val command = when (kind) {
            Kind.Msi -> {
                val script = File(dir, "install-update.cmd")
                // ping as a delay: timeout.exe refuses to run without a console input.
                script.writeText(
                    """
                    @echo off
                    ping -n 3 127.0.0.1 >nul
                    start "" /wait msiexec /i "%~1" /passive /norestart
                    start "" "%~2"
                    """.trimIndent().replace("\n", "\r\n"),
                )
                listOf("cmd", "/c", script.absolutePath, file.absolutePath, app)
            }
            Kind.Deb, Kind.Rpm -> {
                val script = File(dir, "install-update.sh")
                val install = if (kind == Kind.Deb) {
                    "pkexec apt-get install -y \"\$1\""
                } else {
                    "if command -v dnf >/dev/null; then pkexec dnf install -y \"\$1\"; " +
                        "elif command -v zypper >/dev/null; then pkexec zypper --non-interactive install --allow-unsigned-rpm \"\$1\"; " +
                        "else pkexec rpm -U \"\$1\"; fi"
                }
                // Relaunch even if the user cancels the password prompt, so PhoneInput doesn't just vanish.
                script.writeText("#!/bin/sh\nsleep 2\n$install\nexec \"\$2\"\n")
                listOf("setsid", "sh", script.absolutePath, file.absolutePath, app)
            }
            null -> throw IOException("This copy of PhoneInput can't update itself. Download the new version from the releases page.")
        }
        Log.i("Launching update installer: $command")
        ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (HostInfo.os == HostOs.Windows) "NUL" else "/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "install-update.log")))
            .redirectErrorStream(true)
            .start()
    }

    /** Trimmed stdout of a successful [command], or null. */
    private fun output(vararg command: String): String? = runCatching {
        val p = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    }.getOrNull()
}
