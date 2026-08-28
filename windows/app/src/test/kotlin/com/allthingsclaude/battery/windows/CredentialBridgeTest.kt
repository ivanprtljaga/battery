package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.auth.CredentialBridge
import com.allthingsclaude.battery.windows.auth.CredentialLookup
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import com.allthingsclaude.battery.windows.wsl.WslCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CredentialBridgeTest {

    /**
     * The shape a live Claude Code install writes, field for field. Extra keys
     * are present on purpose: they exist in the real file, and the parser must
     * not care.
     */
    private val realShape = """
        {
          "claudeAiOauth": {
            "accessToken": "sk-ant-oat01-example",
            "refreshToken": "sk-ant-ort01-example",
            "expiresAt": 1787944105116,
            "refreshTokenExpiresAt": 1790259027116,
            "scopes": ["user:inference", "user:profile"],
            "subscriptionType": "max",
            "rateLimitTier": "default_claude_max_20x"
          }
        }
    """.trimIndent()

    @Test
    fun `parses the real credential shape`() {
        val tokens = CredentialBridge.parse(realShape)!!
        assertEquals("sk-ant-oat01-example", tokens.accessToken)
        assertEquals(1787944105116L, tokens.expiresAt)
    }

    /**
     * The single most important assertion in this file.
     *
     * Refresh tokens are single-use. If Battery kept the one it can plainly see
     * and ever rotated it, Claude Code's copy would be dead and the user would
     * be signed out of the tool that actually refreshes this credential. The
     * file gives us a refresh token; we must decline it.
     */
    @Test
    fun `withholds the refresh token even though the file has one`() {
        assertNull(CredentialBridge.parse(realShape)!!.refreshToken)
    }

    @Test
    fun `rejects blobs that are not credentials`() {
        assertNull(CredentialBridge.parse("{}"))
        assertNull(CredentialBridge.parse("not json"))
        assertNull(CredentialBridge.parse("""{"claudeAiOauth":{}}"""))
        assertNull(CredentialBridge.parse("""{"claudeAiOauth":{"accessToken":""}}"""))
    }

    private fun wslDir(distro: String = "Ubuntu") =
        ClaudeDir("\\\\wsl.localhost\\$distro\\home\\ivan\\.claude", DirOrigin.WSL, distro)

    private fun command(running: String) = object : WslCommand {
        override fun run(vararg args: String): String = running
    }

    /**
     * The behaviour the whole no-boot design rests on: a stopped distro is
     * answered *without the credential path ever being touched*, because
     * touching it is what starts the machine.
     */
    @Test
    fun `does not read the file when the distro is not running`() {
        var read = false
        val lookup = CredentialBridge.read(
            dir = wslDir(),
            command = command(""),
            readFile = { read = true; null },
        )
        assertIs<CredentialLookup.Unavailable>(lookup)
        assertEquals(CredentialLookup.Reason.DISTRO_NOT_RUNNING, lookup.reason)
        assertEquals(false, read, "the credential path must not be touched for a stopped distro")
    }

    /** And a stopped distro reads as a fact about the machine, not an error. */
    @Test
    fun `names the distro that is not running`() {
        val lookup = CredentialBridge.read(wslDir("Debian"), command(""), { null })
        assertEquals("Debian isn't running", lookup.message)
    }

    @Test
    fun `reads the file when the distro is running`() {
        val lookup = CredentialBridge.read(
            dir = wslDir(),
            command = command("Ubuntu\r\n"),
            readFile = { realShape },
            nowMillis = 1_000L,
        )
        assertIs<CredentialLookup.Available>(lookup)
    }

    @Test
    fun `a missing file is distinguishable from a malformed one`() {
        val missing = CredentialBridge.read(wslDir(), command("Ubuntu\r\n"), { null })
        assertIs<CredentialLookup.Unavailable>(missing)
        assertEquals(CredentialLookup.Reason.FILE_MISSING, missing.reason)

        val malformed = CredentialBridge.read(wslDir(), command("Ubuntu\r\n"), { "{}" })
        assertIs<CredentialLookup.Unavailable>(malformed)
        assertEquals(CredentialLookup.Reason.MALFORMED, malformed.reason)
    }

    /**
     * An expired token is never a sign-in prompt. Battery holds no refresh
     * token by design, so the only true thing to say is that Claude Code will
     * fix it on its next run.
     */
    @Test
    fun `an expired token is reported as expired, not as a sign-in`() {
        val lookup = CredentialBridge.read(
            dir = wslDir(),
            command = command("Ubuntu\r\n"),
            readFile = { realShape },
            nowMillis = 1_787_944_105_117L,
        )
        assertIs<CredentialLookup.Unavailable>(lookup)
        assertEquals(CredentialLookup.Reason.EXPIRED, lookup.reason)
    }

    /** A native directory has no distro to gate on and must not consult wsl.exe. */
    @Test
    fun `native directories are never distro-gated`() {
        var asked = false
        val lookup = CredentialBridge.read(
            dir = ClaudeDir("C:\\Users\\Ivan\\.claude", DirOrigin.WINDOWS_NATIVE),
            command = object : WslCommand {
                override fun run(vararg args: String): String? { asked = true; return "" }
            },
            readFile = { realShape },
            nowMillis = 1_000L,
        )
        assertIs<CredentialLookup.Available>(lookup)
        assertEquals(false, asked)
    }
}
