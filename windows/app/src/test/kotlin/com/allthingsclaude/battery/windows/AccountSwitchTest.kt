package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.StoredTokens
import com.allthingsclaude.battery.windows.auth.CredentialLookup
import com.allthingsclaude.battery.windows.auth.TokenCache
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Signing Claude Code into a different account, in place.
 *
 * The case this exists for: one WSL install, signed in as one person and later
 * as another. Nothing about the *directory* changes, so the source switch does
 * not help — and rule two says the credential is not re-read on the poll,
 * because a read is expensive and on a stopped distro boots a VM.
 *
 * Held to expiry alone, the app would go on reporting the previous account's
 * usage for as long as its token stayed valid — about eight hours — and then
 * silently start reporting the new account's numbers under the old account's
 * name. Both halves of that are covered here.
 */
class AccountSwitchTest {

    private val dir = ClaudeDir("\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.claude", DirOrigin.WSL, "Ubuntu")
    private val now: Instant = Instant.parse("2026-08-29T12:00:00Z")

    /** Valid for eight hours, so expiry can never be what triggers a re-read. */
    private fun token(value: String) = StoredTokens(
        accessToken = value,
        refreshToken = null,
        expiresAt = now.plusSeconds(8 * 3600).toEpochMilli(),
    )

    private class Reader(var current: String) {
        var reads = 0
        fun read(@Suppress("UNUSED_PARAMETER") dir: ClaudeDir): CredentialLookup {
            reads++
            return CredentialLookup.Available(
                StoredTokens(current, null, Instant.parse("2026-08-29T20:00:00Z").toEpochMilli()),
            )
        }
    }

    @Test
    fun `an unchanged credential is not re-read`() {
        val reader = Reader("personal")
        val cache = TokenCache(read = reader::read, stamp = { 1_000L })

        repeat(5) { cache.tokens(dir, now) }

        assertEquals(1, reader.reads, "rule two: one read, then the cache")
    }

    /** The fix. A new credential file means a new account, token still valid. */
    @Test
    fun `a rewritten credential is picked up without waiting for expiry`() {
        val reader = Reader("personal")
        var stamp = 1_000L
        val cache = TokenCache(read = reader::read, stamp = { stamp })

        val before = assertIs<CredentialLookup.Available>(cache.tokens(dir, now))
        assertEquals("personal", before.tokens.accessToken)

        // /logout, /login as work. Claude Code rewrites the file.
        reader.current = "work"
        stamp = 2_000L

        val after = assertIs<CredentialLookup.Available>(cache.tokens(dir, now))
        assertEquals("work", after.tokens.accessToken, "still eight hours of validity left")
        assertEquals(2, reader.reads)
    }

    /**
     * Zero is "cannot look", not "changed" — and the distinction is the whole
     * reason the stamp is gated. A distro shutting down mid-session must leave
     * the gauges standing, which is the behaviour TokenCache already documented.
     */
    @Test
    fun `a distro going down is not mistaken for an account switch`() {
        val reader = Reader("personal")
        var stamp = 1_000L
        val cache = TokenCache(read = reader::read, stamp = { stamp })
        cache.tokens(dir, now)

        stamp = 0L // Wsl.reachable said no.

        val after = assertIs<CredentialLookup.Available>(cache.tokens(dir, now))
        assertEquals("personal", after.tokens.accessToken, "the held token is still good")
        assertEquals(1, reader.reads, "and nothing was read across a downed 9P link")
    }

    @Test
    fun `switching directory still re-reads, stamp or no stamp`() {
        val reader = Reader("personal")
        val cache = TokenCache(read = reader::read, stamp = { 1_000L })
        cache.tokens(dir, now)

        val other = ClaudeDir("C:\\Users\\Ivan\\.claude", DirOrigin.WINDOWS_NATIVE)
        reader.current = "native"

        val after = assertIs<CredentialLookup.Available>(cache.tokens(other, now))
        assertEquals("native", after.tokens.accessToken)
    }
}
