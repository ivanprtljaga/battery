package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.config.ClaudeConfigDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import com.allthingsclaude.battery.windows.wsl.WslCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeConfigDirTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `default directory follows USERPROFILE`() {
        assertEquals(
            "C:\\Users\\Ivan\\.claude",
            ClaudeConfigDir.windowsDefaultDir(env("USERPROFILE" to "C:\\Users\\Ivan")),
        )
    }

    @Test
    fun `CLAUDE_CONFIG_DIR wins`() {
        assertEquals(
            "D:\\work\\claude-alt",
            ClaudeConfigDir.windowsDefaultDir(
                env("USERPROFILE" to "C:\\Users\\Ivan", "CLAUDE_CONFIG_DIR" to "D:\\work\\claude-alt"),
            ),
        )
    }

    @Test
    fun `no USERPROFILE is an ordinary answer`() {
        assertNull(ClaudeConfigDir.windowsDefaultDir(env()))
    }

    /**
     * The special case macOS already carries, and the one that would silently
     * return "no identity" if guessed: a default install keeps `.claude.json`
     * *beside* the directory, not inside it. Confirmed against a live Windows
     * install, which had the file at `%USERPROFILE%\.claude.json` with a
     * complete `oauthAccount` block and nothing inside `.claude\`.
     */
    @Test
    fun `config file sits beside a default directory`() {
        assertEquals(
            "C:\\Users\\Ivan\\.claude.json",
            ClaudeConfigDir.configFile("C:\\Users\\Ivan\\.claude"),
        )
    }

    @Test
    fun `config file sits inside a custom directory`() {
        assertEquals(
            "D:\\work\\claude-alt\\.claude.json",
            ClaudeConfigDir.configFile("D:\\work\\claude-alt"),
        )
    }

    /** The same rule has to hold across the WSL boundary. */
    @Test
    fun `config file sits beside a WSL default directory`() {
        assertEquals(
            "\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.claude.json",
            ClaudeConfigDir.configFile("\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.claude"),
        )
    }

    @Test
    fun `trailing separators do not change identity`() {
        assertEquals(
            ClaudeConfigDir.normalize("C:\\Users\\Ivan\\.claude"),
            ClaudeConfigDir.normalize("C:\\Users\\Ivan\\.claude\\"),
        )
    }

    /** The real `oauthAccount` shape, as read off a live Windows install. */
    @Test
    fun `reads identity from the config block`() {
        val identity = ClaudeConfigDir.identity(
            """
            {
              "userID": "abc",
              "oauthAccount": {
                "accountUuid": "0d9d5a2e-0000-0000-0000-000000000000",
                "emailAddress": "someone@example.com",
                "organizationName": "Example Org",
                "rateLimitTier": "default_claude_max_20x"
              }
            }
            """.trimIndent(),
        )!!
        assertEquals("0d9d5a2e-0000-0000-0000-000000000000", identity.accountUuid)
        assertEquals("someone@example.com", identity.email)
        assertEquals("Example Org", identity.organizationName)
    }

    @Test
    fun `a config with no oauthAccount has no identity`() {
        assertNull(ClaudeConfigDir.identity("""{"userID":"abc"}"""))
        assertNull(ClaudeConfigDir.identity("garbage"))
        assertNull(ClaudeConfigDir.identity("""{"oauthAccount":{"accountUuid":""}}"""))
    }

    @Test
    fun `validity accepts any of the three markers`() {
        assertTrue(ClaudeConfigDir.looksValid("C:\\x\\.claude") { it.endsWith("projects") })
        assertTrue(ClaudeConfigDir.looksValid("C:\\x\\.claude") { it.endsWith("stats-cache.json") })
        assertFalse(ClaudeConfigDir.looksValid("C:\\x\\.claude") { false })
    }

    /**
     * A stopped distro must not appear as a candidate. Building its row means
     * reading its filesystem to see whether Claude Code is there, and that read
     * is what boots it.
     */
    @Test
    fun `only running distros become candidates`() {
        val command = object : WslCommand {
            override fun run(vararg args: String): String =
                if (args.contains("--running")) "Ubuntu\r\n" else "Ubuntu\r\nDebian\r\n"
        }
        val dirs = ClaudeConfigDir.candidates(
            command = command,
            env = env(),
            linuxUserFor = { "ivan" },
            exists = { true },
        )
        assertEquals(1, dirs.size)
        assertEquals("Ubuntu", dirs.single().distro)
        assertEquals(DirOrigin.WSL, dirs.single().origin)
    }

    @Test
    fun `a directory that does not exist is not offered`() {
        val dirs = ClaudeConfigDir.candidates(
            command = object : WslCommand {
                override fun run(vararg args: String): String = "Ubuntu\r\n"
            },
            env = env("USERPROFILE" to "C:\\Users\\Ivan"),
            linuxUserFor = { "ivan" },
            exists = { false },
        )
        assertTrue(dirs.isEmpty())
    }
}
