package com.allthingsclaude.battery.windows.auth

import com.allthingsclaude.battery.core.AppConfig
import com.allthingsclaude.battery.core.StoredTokens
import com.allthingsclaude.battery.windows.config.ClaudeConfigDir
import com.allthingsclaude.battery.windows.config.ClaudeDir
import java.time.Instant

/**
 * Rule two of the three that keep this app from booting the user's distro:
 * **do not re-read the credential on the poll.**
 *
 * A poll happens every 60–180 seconds. An access token, measured on a live
 * install, lasts about eight hours. Re-reading the file each time would cross
 * the 9P boundary roughly 300 times per token for no new information, and on a
 * stopped distro every one of those reads is a VM boot.
 *
 * So the token is held in memory and the file is consulted only as expiry
 * approaches — three-ish times a day. `StoredTokens.isExpiringSoon` and
 * `AppConfig.TOKEN_REFRESH_LEEWAY_SECONDS` already model exactly this; nothing
 * new is invented here.
 *
 * Not thread-safe on purpose: the poll loop is single-threaded, and a lock would
 * imply a concurrency story this does not have.
 */
class TokenCache(
    private val read: (ClaudeDir) -> CredentialLookup = { CredentialBridge.read(it) },
    /**
     * When the credential file last changed, or 0 when it must not be looked at.
     *
     * The cheap half of rule two. Holding a token until it expires is right when
     * the file has not moved, and wrong the moment somebody signs Claude Code
     * into a different account: the old token stays valid for hours, so Battery
     * would go on reporting the previous account's usage while the panel showed
     * whatever it read at startup. A `stat` is not a read — it costs
     * milliseconds and it is gated like everything else here.
     */
    private val stamp: (ClaudeDir) -> Long = {
        ClaudeConfigDir.stampOf(it, ClaudeConfigDir.credentialsFile(it.path))
    },
) {
    private var cached: StoredTokens? = null
    private var cachedFor: String? = null
    private var cachedStamp: Long = 0

    /** What the last file read said, for surfacing a reason without re-reading. */
    var lastLookup: CredentialLookup? = null
        private set

    /**
     * A usable token for [dir], reading the file only if the cache cannot serve.
     *
     * A cached token that is still comfortably valid is returned even when the
     * distro has since shut down — deliberately. The token is a bearer string
     * that works regardless of where it came from, so a distro shutting down
     * mid-session must not blank the gauges; it only matters once the token
     * needs replacing.
     */
    fun tokens(dir: ClaudeDir, now: Instant = Instant.now()): CredentialLookup {
        val current = cached
        val now0 = stamp(dir)
        // Zero is "no answer", not "changed". A distro that has gone down must
        // not read as an account switch — that is the case this whole cache
        // exists to serve.
        val moved = now0 != 0L && now0 != cachedStamp
        if (current != null && cachedFor == dir.path && !moved && !current.isExpiringSoon(now)) {
            return CredentialLookup.Available(current)
        }
        val lookup = read(dir)
        lastLookup = lookup
        if (lookup is CredentialLookup.Available) {
            cached = lookup.tokens
            cachedFor = dir.path
            cachedStamp = now0
        } else if (cachedFor != dir.path) {
            // Only drop a cached token when the question was about a different
            // directory. An unreadable file for the *same* directory leaves the
            // old token in place: it may still have hours left on it.
            cached = null
            cachedFor = null
            cachedStamp = 0
        }
        return lookup
    }

    /** Seconds until the held token wants replacing, or null if nothing is held. */
    fun secondsUntilRefresh(now: Instant = Instant.now()): Long? =
        cached?.let {
            it.expiryInstant.epochSecond - now.epochSecond - AppConfig.TOKEN_REFRESH_LEEWAY_SECONDS
        }

    fun clear() {
        cached = null
        cachedFor = null
        cachedStamp = 0
        lastLookup = null
    }
}
