package com.allthingsclaude.battery.windows.update

import com.allthingsclaude.battery.core.ReleaseFeed
import com.allthingsclaude.battery.core.UsageApi
import com.allthingsclaude.battery.windows.BuildInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The updater, walking the same feed as the other platforms for its own tags.
 *
 * The plan said `ReleaseFeed` would be "reused unchanged". It cannot be, and the
 * reason is worth stating rather than working around quietly:
 *
 * ```
 * const val TAG_PREFIX = "android-v"                 // ReleaseFeed:65
 * "User-Agent" to "Battery-Android",                 // ReleaseFeed:221
 * ```
 *
 * Both are compile-time constants closed over by `findNewer` and `newestOnPage`,
 * so pointing this app at them would offer a Windows user an APK. Making the
 * prefix a parameter is the right change and it belongs upstream — `core` is
 * borrowed from the Android tree in place, and editing it here would diverge the
 * module that this whole build exists to share. Until that lands, this is the
 * page loop written once more with a different constant.
 *
 * What is *not* rewritten is anything that can actually be wrong.
 * [ReleaseFeed.compareVersions] does the numeric comparison,
 * [ReleaseFeed.itemCount] decides where the feed ends, [ReleaseFeed.Check] is
 * the four-way answer, and [ReleaseFeed.shouldAnnounce] holds the skip logic —
 * all prefix-agnostic, all already tested, none duplicated here.
 */
object WindowsRelease {

    /** A fourth tag namespace, beside `v*`, `ios-v*` and `android-v*`. */
    const val TAG_PREFIX = "windows-v"

    /**
     * `ReleaseFeed.PER_PAGE` and `MAX_PAGES` restated, because both are
     * `internal` to `core` and this is a different module.
     *
     * One more line in the same argument as the tag prefix: the shared feed
     * walker is shaped for exactly one caller. Kept identical on purpose — a
     * Windows client that pages differently from the Android one would find a
     * different end of the same feed.
     */
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val REQUEST_HEADERS = mapOf(
        "Accept" to "application/vnd.github+json",
        // GitHub rejects requests without one outright.
        "User-Agent" to "Battery-Windows",
    )

    /**
     * The newest `windows-v*` release above [currentVersion], or why not.
     *
     * Blocking, and up to [MAX_PAGES] requests, so callers keep it
     * off the UI thread. The paging exists for the reason `ReleaseFeed` explains
     * at length: four tag namespaces share one feed and this one will always be
     * the least frequent, so a single page goes blind the moment enough Mac and
     * iOS releases stack on top — and the symptom is silence, which is
     * indistinguishable from good news.
     */
    fun findNewer(
        transport: UsageApi.HttpTransport,
        currentVersion: String,
        // Not ReleaseFeed.DEFAULT_REPO: that is hardcoded upstream, and a fork
        // built from it would poll allthingsclaude/battery, find no newer tag,
        // and call itself current for ever.
        repo: String = BuildInfo.RELEASE_REPO,
    ): ReleaseFeed.Check {
        for (page in 1..MAX_PAGES) {
            val response = runCatching {
                transport.request(releasesUrl(repo, page), "GET", REQUEST_HEADERS, null)
            }.getOrNull() ?: return ReleaseFeed.Check.Failed(ReleaseFeed.Check.Reason.OFFLINE)

            if (response.code != 200) {
                return ReleaseFeed.Check.Failed(
                    when {
                        response.code == 429 -> ReleaseFeed.Check.Reason.RATE_LIMITED
                        // GitHub answers a spent unauthenticated quota with 403
                        // rather than 429, and the two are the same thing to a
                        // user staring at "couldn't check".
                        response.code == 403 -> ReleaseFeed.Check.Reason.RATE_LIMITED
                        else -> ReleaseFeed.Check.Reason.SERVER
                    },
                )
            }

            newestOnPage(response.body)?.let { newest ->
                // The feed is newest-first, so the first Windows release found is
                // the newest one. Whether it is an *update* is a separate
                // question, and either way there is nothing further back to read.
                return if (ReleaseFeed.compareVersions(newest.version, currentVersion) > 0) {
                    ReleaseFeed.Check.Available(newest)
                } else {
                    ReleaseFeed.Check.UpToDate
                }
            }

            // A short page is the end of the feed.
            if (ReleaseFeed.itemCount(response.body) < PER_PAGE) {
                return ReleaseFeed.Check.NoRelease
            }
        }
        // Five hundred releases deep and no Windows tag. Something is wrong with
        // the feed or the prefix, and the one thing this must not do is shrug and
        // call it up to date.
        return ReleaseFeed.Check.NoRelease
    }

    /** The newest `windows-v*` release on one page, ignoring version. */
    fun newestOnPage(body: String): ReleaseFeed.Release? = runCatching {
        val newest = json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["tag_name"]?.jsonPrimitive?.content?.startsWith(TAG_PREFIX) == true }
            ?: return null
        ReleaseFeed.Release(
            newest["tag_name"]!!.jsonPrimitive.content.removePrefix(TAG_PREFIX),
            newest["html_url"]?.jsonPrimitive?.content.orEmpty(),
        )
    }.getOrNull()

    internal fun releasesUrl(repo: String, page: Int) =
        "https://api.github.com/repos/$repo/releases?per_page=$PER_PAGE&page=$page"
}
