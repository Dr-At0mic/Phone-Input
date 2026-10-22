package app.phoneinput.desktop.audio

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

sealed class InstallState {
    data object Idle : InstallState()
    data class Downloading(val progress: Float?) : InstallState()
    data object WaitingForSetup : InstallState()

    /** Setup finished but the device isn't visible yet; VB-Audio says a reboot finalizes installation. */
    data object NeedsRestart : InstallState()
    data class Failed(val message: String) : InstallState()
}

/**
 * Fetches the official VB-CABLE driver pack from vb-audio.com and opens VB-Audio's own setup
 * (elevated, via UAC) so the user installs it from inside PhoneInput.
 *
 * The VB-CABLE license forbids integrating its package into another installer without the
 * author's agreement, so PhoneInput never bundles it or installs it silently: the user still
 * sees and confirms VB-Audio's setup.
 */
class VbCableInstaller(
    private val workDir: File = File(System.getProperty("java.io.tmpdir"), "PhoneInput/vbcable"),
) {
    /** Downloads, verifies and launches setup. Blocks until setup closes. Returns false if cancelled. */
    fun run(onProgress: (Float?) -> Unit): Boolean {
        workDir.mkdirs()
        val zip = File(workDir, "VBCABLE_Driver_Pack45.zip")
        if (!zip.isFile || sha256(zip) != PACK_SHA256) download(zip, onProgress)
        if (sha256(zip) != PACK_SHA256) {
            zip.delete()
            throw IOException("The downloaded driver didn't match the expected file. Try again later.")
        }
        val dir = File(workDir, "pack").also { it.deleteRecursively(); it.mkdirs() }
        extract(zip, dir)
        // The x64 setup also covers ARM64 Windows (the pack ships vbaudio_cable64arm_win10.sys).
        val setup = File(dir, "VBCABLE_Setup_x64.exe")
        if (!setup.isFile) throw IOException("Setup program missing from the driver pack.")
        onProgress(1f) // download phase over (also when a cached pack was reused)
        return launchElevated(setup)
    }

    private fun download(target: File, onProgress: (Float?) -> Unit) {
        val conn = URI(PACK_URL).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "PhoneInput")
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (HTTP ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 }
            val tmp = File(target.path + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(total?.let { done.toFloat() / it })
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't save the driver download.")
        } finally {
            conn.disconnect()
        }
    }

    private fun extract(zip: File, dir: File) {
        val root = dir.canonicalFile
        ZipInputStream(zip.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("Unexpected file in driver pack: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    /** Runs setup through UAC. Start-Process throws (exit 1) when the user declines the prompt. */
    private fun launchElevated(setup: File): Boolean {
        val path = setup.absolutePath.replace("'", "''")
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "Start-Process -FilePath '$path' -WorkingDirectory '${setup.parentFile.absolutePath.replace("'", "''")}' -Verb RunAs -Wait",
        ).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor() == 0
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PACK_URL = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip"

        /** SHA-256 of VBCABLE_Driver_Pack45.zip as published on 2026-10-22. */
        const val PACK_SHA256 = "b950e39f01af1d04ea623c8f6d8eb9b6ea5c477c637295fabf20631c85116bfb"
    }
}
