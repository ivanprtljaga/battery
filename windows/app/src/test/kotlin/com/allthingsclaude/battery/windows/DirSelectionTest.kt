package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.StoredTokens
import com.allthingsclaude.battery.windows.auth.CredentialLookup
import com.allthingsclaude.battery.windows.auth.DirSelection
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirSelectionTest {

    private val native = ClaudeDir("C:\\Users\\Ivan\\.claude", DirOrigin.WINDOWS_NATIVE)
    private val wsl = ClaudeDir(
        "\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.claude",
        DirOrigin.WSL,
        "Ubuntu",
    )

    private fun available() = CredentialLookup.Available(StoredTokens("token", null, Long.MAX_VALUE))
    private fun missing() = CredentialLookup.Unavailable(CredentialLookup.Reason.FILE_MISSING)

    /**
     * The exact case observed on a real Windows machine: a native install that
     * was signed in once and kept its `.claude.json` — so it looks valid and
     * reports an identity — sitting ahead of the live WSL install that actually
     * holds a credential. Taking the first candidate blocked the app on the
     * empty one while a token with six hours left sat below it.
     */
    @Test
    fun `skips a signed-out directory for one that can answer`() {
        val picked = DirSelection.pick(listOf(native, wsl)) {
            if (it == wsl) available() else missing()
        }
        assertEquals(wsl, picked)
    }

    /** Order is still honoured when more than one can answer. */
    @Test
    fun `prefers the earliest candidate that works`() {
        val picked = DirSelection.pick(listOf(native, wsl)) { available() }
        assertEquals(native, picked)
    }

    /**
     * When nothing can answer, the first candidate is still returned — it has a
     * name, an identity and a reason, all of which say more than an empty panel.
     */
    @Test
    fun `falls back to the first candidate when none can answer`() {
        val picked = DirSelection.pick(listOf(native, wsl)) { missing() }
        assertEquals(native, picked)
    }

    @Test
    fun `no candidates is no selection`() {
        assertNull(DirSelection.pick(emptyList()) { missing() })
    }

    /**
     * Selection must not read past the directory it settles on: each read is a
     * filesystem hit, and for a WSL candidate that is a trip across 9P.
     */
    @Test
    fun `stops reading once it finds one that works`() {
        val seen = mutableListOf<ClaudeDir>()
        DirSelection.pick(listOf(native, wsl)) { seen += it; available() }
        assertEquals(listOf(native), seen)
    }
}
