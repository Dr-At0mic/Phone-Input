package app.phoneinput.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    @Test
    fun parsesVersions() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parse("1.0.0"))
        assertEquals(AppVersion(2, 10, 0), AppVersion.parse("v2.10.0-beta"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(null))
    }

    @Test
    fun comparesNumerically() {
        assertTrue(AppVersion.parse("1.10.0")!! > AppVersion.parse("1.9.9")!!)
        assertTrue(AppVersion.parse("2.0.0")!! > AppVersion.parse("1.99.99")!!)
        assertEquals(0, AppVersion(1, 0, 0).compareTo(AppVersion.parse("v1.0.0")!!))
    }

    @Test
    fun parsesGithubRelease() {
        val release = ReleaseFeed.parse(
            """
            {"tag_name":"v1.1.0","html_url":"https://github.com/x/y/releases/tag/v1.1.0","draft":false,"prerelease":false,
             "body":"Notes","extra":{"nested":[1,2]},
             "assets":[
               {"name":"PhoneInput-1.1.0.apk","browser_download_url":"https://example.com/a.apk","size":9566342},
               {"name":"SHA256SUMS.txt","browser_download_url":"https://example.com/sums","size":361}
             ]}
            """.trimIndent(),
        )!!
        assertEquals(AppVersion(1, 1, 0), release.version)
        assertEquals("Notes", release.notes)
        assertEquals(2, release.assets.size)
        assertEquals(9566342L, release.asset("PhoneInput-1.1.0.apk")!!.size)
    }

    @Test
    fun skipsPrereleasesAndJunk() {
        assertNull(ReleaseFeed.parse("""{"tag_name":"v2.0.0","prerelease":true,"assets":[]}"""))
        assertNull(ReleaseFeed.parse("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(ReleaseFeed.parse("not json"))
    }

    @Test
    fun parsesChecksums() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = ReleaseFeed.parseChecksums("$a  PhoneInput-1.0.0.apk\n$b *phoneinput_1.0.0_amd64.deb\n\nbad line\n")
        assertEquals(mapOf("PhoneInput-1.0.0.apk" to a, "phoneinput_1.0.0_amd64.deb" to b.lowercase()), sums)
    }
}
