package app.phoneinput.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Checks GitHub for a newer release, downloads the file for this platform, verifies it against the
 * release's SHA256SUMS.txt and hands it to the platform installer. Shared by Android and desktop;
 * each passes how to pick its asset and how to install it.
 */
class Updater(
    /** Null for development builds: updates stay [UpdateState.Disabled]. */
    val currentVersion: AppVersion?,
    private val downloadDir: File,
    private val scope: CoroutineScope,
    /** The asset this installation can update itself from, or null (the user gets a link to the page). */
    private val pickAsset: (Release) -> ReleaseAsset?,
    /** Starts installing [file]. Throws with a user-facing message on failure. */
    private val install: (file: File, release: Release) -> Unit,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<UpdateState>(if (currentVersion == null) UpdateState.Disabled else UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var downloaded: File? = null

    /** Looks for a newer release; with [autoDownload] it is downloaded right away so installing is one click. */
    fun check(autoDownload: Boolean) {
        val current = currentVersion ?: return
        if (job?.isActive == true) return
        when (_state.value) {
            is UpdateState.Ready, is UpdateState.Installing -> return
            else -> {}
        }
        job = scope.launch(Dispatchers.IO) {
            _state.value = UpdateState.Checking
            val release = try {
                fetchLatest()
            } catch (e: IOException) {
                log("Update check failed", e)
                _state.value = UpdateState.Failed("Couldn't check for updates. Check your internet connection.")
                return@launch
            }
            if (release == null || release.version <= current) {
                _state.value = UpdateState.UpToDate
                return@launch
            }
            val asset = pickAsset(release)
            log("Update available: ${release.version} (asset ${asset?.name})", null)
            _state.value = UpdateState.Available(release, asset)
            if (autoDownload && asset != null) downloadNow(release, asset)
        }
    }

    /** Downloads the update found by [check]. */
    fun download() {
        val available = _state.value as? UpdateState.Available ?: return
        val asset = available.asset ?: return
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { downloadNow(available.release, asset) }
    }

    /** Installs the downloaded update. On desktop the installer restarts the app. */
    fun install() {
        val ready = _state.value as? UpdateState.Ready ?: return
        val file = downloaded ?: return
        _state.value = UpdateState.Installing(ready.release)
        try {
            install(file, ready.release)
        } catch (e: Exception) {
            log("Update install failed", e)
            _state.value = UpdateState.Failed(e.message ?: "Couldn't install the update.")
        }
    }

    /** The platform installer gave up (e.g. the user cancelled); go back to "ready to install". */
    fun installFailed(message: String?) {
        val installing = _state.value as? UpdateState.Installing ?: return
        _state.value = if (message == null) UpdateState.Ready(installing.release) else UpdateState.Failed(message)
    }

    private fun downloadNow(release: Release, asset: ReleaseAsset) {
        _state.value = UpdateState.Downloading(release, null)
        try {
            val checksums = release.asset(ReleaseFeed.CHECKSUMS)?.let { ReleaseFeed.parseChecksums(fetchText(it.url)) }.orEmpty()
            val expected = checksums[asset.name] ?: throw IOException("This release has no checksum for ${asset.name}.")
            downloadDir.mkdirs()
            downloadDir.listFiles()?.filter { it.name != asset.name }?.forEach { it.delete() }
            val target = File(downloadDir, asset.name)
            if (!target.isFile || sha256(target) != expected) {
                downloadFile(asset, target) { p -> _state.value = UpdateState.Downloading(release, p) }
                if (sha256(target) != expected) {
                    target.delete()
                    throw IOException("The download was damaged. Try again.")
                }
            }
            downloaded = target
            log("Update ${release.version} downloaded and verified: $target", null)
            _state.value = UpdateState.Ready(release)
        } catch (e: IOException) {
            log("Update download failed", e)
            _state.value = UpdateState.Failed(e.message ?: "Couldn't download the update.")
        }
    }

    private fun fetchLatest(): Release? {
        val conn = open(ReleaseFeed.LATEST_API)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            return when (conn.responseCode) {
                200 -> ReleaseFeed.parse(conn.inputStream.bufferedReader().readText())
                404 -> null // no releases yet
                else -> throw IOException("GitHub answered HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (HTTP ${conn.responseCode}).")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(asset: ReleaseAsset, target: File, onProgress: (Float?) -> Unit) {
        val conn = open(asset.url)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (HTTP ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size.takeIf { it > 0 }
            val tmp = File(target.path + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = total?.let { (100 * done / it).toInt() } ?: -1
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(total?.let { done.toFloat() / it })
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Couldn't save the update.")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "PhoneInput/${currentVersion ?: "dev"}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** How often a running app looks for updates. GitHub allows 60 unauthenticated API calls per hour. */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
