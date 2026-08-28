package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.wsl.Wsl
import com.allthingsclaude.battery.windows.wsl.WslCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WslTest {

    /**
     * The exact bytes a live `wsl.exe -l --running -q` produced, decoded. Two
     * things here would each silently break every lookup: the CRLF, and the
     * trailing blank line.
     */
    @Test
    fun `parses real wsl output`() {
        assertEquals(listOf("Ubuntu"), Wsl.parseDistroList("Ubuntu\r\n"))
    }

    @Test
    fun `parses several distros`() {
        assertEquals(
            listOf("Ubuntu", "Debian", "docker-desktop"),
            Wsl.parseDistroList("Ubuntu\r\nDebian\r\ndocker-desktop\r\n"),
        )
    }

    /** A BOM would otherwise make the first distro unmatchable. */
    @Test
    fun `strips a byte order mark`() {
        assertEquals(listOf("Ubuntu"), Wsl.parseDistroList("\uFEFFUbuntu\r\n"))
    }

    /** No distro running, and no wsl.exe at all, are the same ordinary answer. */
    @Test
    fun `empty and null are both no distros`() {
        assertEquals(emptyList<String>(), Wsl.parseDistroList(""))
        assertEquals(emptyList<String>(), Wsl.parseDistroList(null))
        assertEquals(emptyList<String>(), Wsl.parseDistroList("\r\n\r\n"))
    }

    /**
     * Distro names contain spaces in the wild ("Ubuntu 22.04"), so trimming must
     * not split them.
     */
    @Test
    fun `keeps internal spaces`() {
        assertEquals(listOf("Ubuntu 22.04"), Wsl.parseDistroList("Ubuntu 22.04\r\n"))
    }

    @Test
    fun `builds UNC paths with backslashes`() {
        assertEquals(
            "\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.claude",
            Wsl.uncPath("Ubuntu", "/home/ivan/.claude"),
        )
    }

    /** `running` must ask the free question, never `-l` on its own. */
    @Test
    fun `running asks only for running distros`() {
        var asked: List<String>? = null
        val spy = object : WslCommand {
            override fun run(vararg args: String): String? {
                asked = args.toList()
                return "Ubuntu\r\n"
            }
        }
        assertEquals(listOf("Ubuntu"), Wsl.running(spy))
        assertTrue("--running" in asked!!, "must pass --running, got $asked")
    }
}
