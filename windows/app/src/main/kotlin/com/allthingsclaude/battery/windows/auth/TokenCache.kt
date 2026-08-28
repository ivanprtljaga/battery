package com.allthingsclaude.battery.windows.auth

import com.allthingsclaude.battery.core.AppConfig
import com.allthingsclaude.battery.core.StoredTokens
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
) {
    private var cached: StoredTokens? = null
    private var cachedFor: String? = null

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
        if (current != null && cachedFor == dir.path && !current.isExpiringSoon(now)) {
            return CredentialLookup.Available(current)
        }
        val lookup = read(dir)
        lastLookup = lookup
        if (lookup is CredentialLookup.Available) {
            cached = lookup.tokens
            cachedFor = dir.path
        } else if (cachedFor != dir.path) {
            // Only drop a cached token when the question was about a different
            // directory. An unreadable file for the *same* directory leaves the
            // old token in place: it may still have hours left on it.
            cached = null
            cachedFor = null
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
        lastLookup = null
    }
}
