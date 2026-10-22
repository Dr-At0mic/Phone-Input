package app.phoneinput.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A version like 1.2.3. Tags may start with "v"; anything after the patch number ("-beta") is ignored. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

        fun parse(text: String?): AppVersion? {
            val match = text?.trim()?.let { PATTERN.find(it) } ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class Release(
    val version: AppVersion,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

/** Where updates come from: the GitHub releases of the project, published by CI for each v* tag. */
object ReleaseFeed {
    const val REPO = "Dr-At0mic/Phone-Input"
    const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** `sha256sum` output listing every asset; downloads are rejected unless they match it. */
    const val CHECKSUMS = "SHA256SUMS.txt"

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses GitHub's "latest release" JSON. Null for drafts, prereleases, or tags that aren't versions. */
    fun parse(body: String): Release? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        if (root.bool("draft") || root.bool("prerelease")) return null
        val tag = root.string("tag_name") ?: return null
        Release(
            version = AppVersion.parse(tag) ?: return null,
            tag = tag,
            notes = root.string("body").orEmpty(),
            pageUrl = root.string("html_url") ?: RELEASES_PAGE,
            assets = root["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
                val asset = element.jsonObject
                ReleaseAsset(
                    name = asset.string("name") ?: return@mapNotNull null,
                    url = asset.string("browser_download_url") ?: return@mapNotNull null,
                    size = asset["size"]?.jsonPrimitive?.longOrNull ?: -1,
                )
            },
        )
    }.getOrNull()

    /** `<hex digest>  <file name>` lines (text or binary mode) → file name to lowercase digest. */
    fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2 || parts[0].length != 64) null else parts[1].removePrefix("*") to parts[0].lowercase()
        }
        .toMap()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true
}

/** What the app knows about updates, shown in the UI. */
sealed interface UpdateState {
    /** Development build (no version to compare), so updates are off. */
    data object Disabled : UpdateState

    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    /** A newer version exists. [asset] is null when it can't be installed from inside the app (open the page instead). */
    data class Available(val release: Release, val asset: ReleaseAsset?) : UpdateState

    data class Downloading(val release: Release, val progress: Float?) : UpdateState

    /** Downloaded and verified; installing restarts the app. */
    data class Ready(val release: Release) : UpdateState

    data class Installing(val release: Release) : UpdateState

    data class Failed(val message: String) : UpdateState
}
