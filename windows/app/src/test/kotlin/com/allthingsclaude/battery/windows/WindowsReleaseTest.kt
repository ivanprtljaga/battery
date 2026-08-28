package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.ReleaseFeed
import com.allthingsclaude.battery.core.UsageApi
import com.allthingsclaude.battery.windows.update.WindowsRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The updater's feed walk.
 *
 * The interesting cases are all about *not* claiming to be up to date when the
 * feed simply could not answer — the failure `ReleaseFeed` was written to stop
 * happening on Android, arriving here for the same reason: four tag namespaces
 * share one feed and `windows-v*` will always be the rarest on it.
 */
class WindowsReleaseTest {

    private fun release(tag: String) =
        """{"tag_name":"$tag","html_url":"https://example.test/$tag"}"""

    private fun page(vararg tags: String) = tags.joinToString(",", "[", "]") { release(it) }

    /** A page of 100 non-Windows releases, so the walker has to page on. */
    private fun fullPageOfOthers(from: Int) =
        page(*(from until from + 100).map { "v0.$it.0" }.toTypedArray())

    private fun transport(vararg pages: String) = object : UsageApi.HttpTransport {
        val asked = mutableListOf<String>()
        override fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
        ): UsageApi.HttpTransport.Response {
            asked += url
            // `[?&]` matters: a bare `page=` matches inside `per_page=100` first.
            val index = Regex("[?&]page=(\\d+)").find(url)!!.groupValues[1].toInt() - 1
            return UsageApi.HttpTransport.Response(200, pages.getOrElse(index) { "[]" })
        }
    }

    private fun failing(code: Int) = object : UsageApi.HttpTransport {
        override fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
        ) = UsageApi.HttpTransport.Response(code, "")
    }

    @Test
    fun `a newer windows release is offered`() {
        val check = WindowsRelease.findNewer(transport(page("windows-v0.2.0")), "0.1.0")

        val available = assertIs<ReleaseFeed.Check.Available>(check)
        assertEquals("0.2.0", available.release.version)
        assertEquals("https://example.test/windows-v0.2.0", available.release.url)
    }

    @Test
    fun `the same version is up to date`() {
        assertIs<ReleaseFeed.Check.UpToDate>(
            WindowsRelease.findNewer(transport(page("windows-v0.1.0")), "0.1.0"),
        )
    }

    @Test
    fun `an older release is up to date, not an offer`() {
        assertIs<ReleaseFeed.Check.UpToDate>(
            WindowsRelease.findNewer(transport(page("windows-v0.1.0")), "0.2.0"),
        )
    }

    /** The whole reason ReleaseFeed pages, inherited: three louder namespaces. */
    @Test
    fun `other platforms tags are skipped, not mistaken for this one`() {
        val check = WindowsRelease.findNewer(
            transport(page("v1.9.0", "ios-v1.4.0", "android-v0.3.0", "windows-v0.2.0")),
            "0.1.0",
        )
        assertEquals("0.2.0", assertIs<ReleaseFeed.Check.Available>(check).release.version)
    }

    @Test
    fun `the walk continues past a full page with no windows release`() {
        val transport = transport(fullPageOfOthers(1), page("windows-v0.4.0"))

        val check = WindowsRelease.findNewer(transport, "0.1.0")

        assertEquals("0.4.0", assertIs<ReleaseFeed.Check.Available>(check).release.version)
        assertEquals(2, transport.asked.size, "one page was short of an answer")
        assertTrue(transport.asked[1].endsWith("page=2"), transport.asked[1])
    }

    /**
     * The distinction that matters. A feed with no Windows release at all claims
     * nothing — reporting it as up to date would be a lie shaped like good news,
     * and it is the true state of the repository until the first release ships.
     */
    @Test
    fun `a feed with no windows release says so rather than up to date`() {
        val check = WindowsRelease.findNewer(transport(page("v1.9.0", "ios-v1.4.0")), "0.1.0")
        assertIs<ReleaseFeed.Check.NoRelease>(check)
    }

    @Test
    fun `the walk stops at the page cap rather than running for ever`() {
        val transport = transport(*(1..10).map { fullPageOfOthers(it * 100) }.toTypedArray())

        assertIs<ReleaseFeed.Check.NoRelease>(WindowsRelease.findNewer(transport, "0.1.0"))
        assertEquals(5, transport.asked.size, "MAX_PAGES, and then it gives up honestly")
    }

    @Test
    fun `a spent quota is rate limited, not a server fault`() {
        for (code in listOf(403, 429)) {
            val check = WindowsRelease.findNewer(failing(code), "0.1.0")
            assertEquals(
                ReleaseFeed.Check.Reason.RATE_LIMITED,
                assertIs<ReleaseFeed.Check.Failed>(check).reason,
                "HTTP $code",
            )
        }
    }

    @Test
    fun `anything else is a server fault`() {
        assertEquals(
            ReleaseFeed.Check.Reason.SERVER,
            assertIs<ReleaseFeed.Check.Failed>(WindowsRelease.findNewer(failing(500), "0.1.0")).reason,
        )
    }

    @Test
    fun `a transport that throws is offline`() {
        val broken = object : UsageApi.HttpTransport {
            override fun request(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: String?,
            ): UsageApi.HttpTransport.Response = throw java.io.IOException("no route")
        }
        assertEquals(
            ReleaseFeed.Check.Reason.OFFLINE,
            assertIs<ReleaseFeed.Check.Failed>(WindowsRelease.findNewer(broken, "0.1.0")).reason,
        )
    }

    @Test
    fun `a malformed page is no release, not a crash`() {
        assertNull(WindowsRelease.newestOnPage("not json"))
        assertNull(WindowsRelease.newestOnPage("{}"))
        assertNull(WindowsRelease.newestOnPage("[]"))
    }

    @Test
    fun `the url asks GitHub for this repo's releases`() {
        val url = WindowsRelease.releasesUrl("allthingsclaude/battery", 3)
        assertEquals(
            "https://api.github.com/repos/allthingsclaude/battery/releases?per_page=100&page=3",
            url,
        )
    }
}
