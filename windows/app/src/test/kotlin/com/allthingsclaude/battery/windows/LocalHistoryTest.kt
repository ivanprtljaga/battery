package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import com.allthingsclaude.battery.windows.history.LocalHistory
import com.allthingsclaude.battery.windows.history.ProjectUsage
import com.allthingsclaude.battery.windows.wsl.WslCommand
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalHistoryTest {

    private val root: File = File.createTempFile("battery-history", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val projects = File(root, "projects").also { it.mkdirs() }

    /** Friday, so "seven days back" does not cross a month or a year boundary. */
    private val now: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private val utc: ZoneId = ZoneOffset.UTC

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** No distros running, so a native directory is the reachable case. */
    private val noDistros = object : WslCommand {
        override fun run(vararg args: String): String? = ""
    }

    private fun dir(origin: DirOrigin = DirOrigin.WINDOWS_NATIVE, distro: String? = null) =
        ClaudeDir(root.absolutePath, origin, distro)

    private fun session(
        project: String,
        name: String = "session.jsonl",
        lines: List<String>,
        modified: Instant = now,
    ): File {
        val folder = File(projects, project).also { it.mkdirs() }
        return File(folder, name).also {
            it.writeText(lines.joinToString("\n"))
            it.setLastModified(modified.toEpochMilli())
        }
    }

    private fun assistant(at: String, cwd: String?, input: Long = 0, output: Long = 0, cacheRead: Long = 0) =
        buildString {
            append("""{"type":"assistant","timestamp":"$at",""")
            if (cwd != null) append(""""cwd":"$cwd",""")
            append(""""message":{"usage":{"input_tokens":$input,"output_tokens":$output,""")
            append(""""cache_creation_input_tokens":0,"cache_read_input_tokens":$cacheRead}}}""")
        }

    @Test
    fun `seven days come back zero-filled and oldest first`() {
        session("-home-me-app", lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/app", output = 5)))

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))

        assertEquals(7, stats.days.size)
        assertEquals("2026-08-22", stats.days.first().date.toString(), "oldest first")
        assertEquals("2026-08-28", stats.days.last().date.toString(), "today last")
        assertEquals(0L, stats.days.first().tokens, "a quiet day is a zero, not a gap")
        assertEquals(5L, stats.days.last().tokens)
    }

    /** All four counters, matching StatsCacheService.tokenCount. */
    @Test
    fun `every token counter is summed`() {
        session(
            "-home-me-app",
            lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/app", input = 2, output = 3, cacheRead = 100)),
        )

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(105L, stats.days.last().tokens)
    }

    @Test
    fun `entries older than the window are not counted`() {
        session(
            "-home-me-app",
            lines = listOf(
                assistant("2026-08-01T09:00:00Z", "/home/me/app", output = 999),
                assistant("2026-08-28T09:00:00Z", "/home/me/app", output = 7),
            ),
        )

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(7L, stats.days.sumOf { it.tokens })
    }

    /**
     * The read-avoidance that makes this affordable: a file last written before
     * the window opened cannot hold a line inside it, so it is never opened.
     */
    @Test
    fun `a file untouched since before the window is never opened`() {
        // Contents inside the window, mtime long before it. The combination
        // cannot occur in life, which is what makes it the right probe: the
        // tokens are only invisible if the file was never read.
        session(
            "-home-me-old",
            lines = listOf(assistant("2026-08-27T09:00:00Z", "/home/me/old", output = 500)),
            modified = Instant.parse("2026-07-01T00:00:00Z"),
        )

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(0L, stats.days.sumOf { it.tokens }, "skipped by mtime, not by timestamp")
        assertTrue(stats.projects.isEmpty())
    }

    @Test
    fun `projects are named from the working directory, biggest first`() {
        session("-home-me-a", lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/dev/battery", output = 100)))
        session("-home-me-b", lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/dev/dotfiles", output = 300)))

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(listOf("dotfiles", "battery"), stats.projects.map { it.name })
        assertEquals(listOf(300L, 100L), stats.projects.map { it.tokens })
    }

    @Test
    fun `a line with no cwd falls back to the transcript folder name`() {
        session("-home-ivan-dev-repos-markoradak-battery", lines = listOf(assistant("2026-08-28T09:00:00Z", null, output = 9)))

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(listOf("battery"), stats.projects.map { it.name })
    }

    @Test
    fun `the breakdown is capped`() {
        repeat(LocalHistory.MAX_PROJECTS + 3) { index ->
            session("-p$index", lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/p$index", output = (index + 1).toLong())))
        }

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(LocalHistory.MAX_PROJECTS, stats.projects.size)
    }

    @Test
    fun `lines without usage are skipped rather than failing the read`() {
        session(
            "-home-me-app",
            lines = listOf(
                """{"type":"user","timestamp":"2026-08-28T09:00:00Z","cwd":"/home/me/app"}""",
                """{"type":"file-history-snapshot","snapshot":{}}""",
                """not json at all""",
                "",
                assistant("2026-08-28T10:00:00Z", "/home/me/app", output = 4),
                """{"type":"assistant","timestamp":"2026-08-28T10:01:00Z","message":{"usage":{"inp""",
            ),
        )

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(4L, stats.days.sumOf { it.tokens }, "a torn last line costs one message, not the file")
    }

    /**
     * The rule the whole app is built around. A WSL directory whose distro is
     * down must not be walked — `File.walkTopDown` on a `\\wsl.localhost` path
     * is exactly what starts the machine.
     */
    @Test
    fun `a stopped distro reads nothing at all`() {
        session("-home-me-app", lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/app", output = 5)))

        val stats = LocalHistory.read(dir(DirOrigin.WSL, "Ubuntu"), now, utc, noDistros)
        assertNull(stats, "null, not empty: a down distro is not a quiet week")
    }

    @Test
    fun `a running distro is read`() {
        session("-home-me-app", lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/app", output = 5)))
        val running = object : WslCommand {
            override fun run(vararg args: String): String = "Ubuntu\r\n"
        }

        val stats = assertNotNull(LocalHistory.read(dir(DirOrigin.WSL, "Ubuntu"), now, utc, running))
        assertEquals(5L, stats.days.sumOf { it.tokens })
    }

    /**
     * Taken from the stat pass before the window filter, so a live session in a
     * project that has been quiet all week still counts as activity.
     */
    @Test
    fun `last activity is the newest write anywhere, window or not`() {
        session(
            "-home-me-old",
            lines = listOf(assistant("2026-07-01T09:00:00Z", "/home/me/old", output = 1)),
            modified = Instant.parse("2026-08-28T11:59:00Z"),
        )
        session(
            "-home-me-app",
            lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/app", output = 1)),
            modified = Instant.parse("2026-08-20T00:00:00Z"),
        )

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(Instant.parse("2026-08-28T11:59:00Z"), stats.lastActivity)
    }

    @Test
    fun `a missing projects folder is empty, not a crash`() {
        projects.deleteRecursively()

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(7, stats.days.size)
        assertEquals(0L, stats.totalTokens)
        assertTrue(stats.projects.isEmpty())
    }

    /**
     * The bug real data found. `cwd` is per message and drifts as a session
     * works — one session here logged 224 messages at `…/battery`, 63 at
     * `…/battery/windows` and 3 at `…/battery/android`. Grouping by it ranks
     * where somebody was standing, not what they worked on.
     */
    @Test
    fun `one session is one project however much its cwd wanders`() {
        session(
            "-home-me-dev-battery",
            lines = listOf(
                assistant("2026-08-28T09:00:00Z", "/home/me/dev/battery", output = 224),
                assistant("2026-08-28T09:10:00Z", "/home/me/dev/battery/windows", output = 63),
                assistant("2026-08-28T09:20:00Z", "/home/me/dev/battery/android", output = 3),
            ),
        )

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(listOf("battery"), stats.projects.map { it.name })
        assertEquals(listOf(290L), stats.projects.map { it.tokens })
    }

    /** Two sessions in the same project folder are still one project. */
    @Test
    fun `separate sessions in one project are added together`() {
        session("-home-me-dev-battery", "a.jsonl", listOf(assistant("2026-08-28T09:00:00Z", "/home/me/dev/battery", output = 10)))
        session("-home-me-dev-battery", "b.jsonl", listOf(assistant("2026-08-28T10:00:00Z", "/home/me/dev/battery", output = 20)))

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(listOf(ProjectUsage("battery", 30L)), stats.projects)
    }

    @Test
    fun `a plain repo keeps its bare name`() {
        assertEquals("battery", LocalHistory.projectName("/home/ivan/dev/battery"))
        assertEquals("battery", LocalHistory.projectName("/home/ivan/dev/battery/"))
        assertEquals("Ivan", LocalHistory.projectName("C:\\Users\\Ivan"))
        assertEquals("solo", LocalHistory.projectName("solo"))
    }

    /** A generic leaf is qualified with its repo — "windows" names nothing. */
    @Test
    fun `a generic leaf is qualified with the repo`() {
        assertEquals("battery/windows", LocalHistory.projectName("/home/ivan/dev/battery/windows"))
        assertEquals("battery/core", LocalHistory.projectName("/home/ivan/dev/battery/core"))
        assertEquals("shiftover/web", LocalHistory.projectName("/home/ivan/dev/shiftover/web"))
    }

    /** …and a package under a monorepo container is named by the repo. */
    @Test
    fun `a monorepo package is qualified with the repo`() {
        assertEquals("mozhe/landing", LocalHistory.projectName("/home/ivan/dev/mozhe/apps/landing"))
        assertEquals("mozhe/parser", LocalHistory.projectName("/home/ivan/dev/mozhe/packages/parser"))
    }

    @Test
    fun `an unusable path has no name`() {
        assertNull(LocalHistory.projectName(null))
        assertNull(LocalHistory.projectName("///"))
        assertNull(LocalHistory.projectName(""))
    }

    /**
     * The folder encoding is lossy — a directory with a real dash in its name is
     * indistinguishable from a separator — which is why an observed `cwd` wins
     * and this is only the fallback.
     */
    @Test
    fun `a transcript folder decodes back to a path`() {
        assertEquals(
            "/home/ivan/dev/repos/markoradak/battery",
            LocalHistory.decodeFolder("-home-ivan-dev-repos-markoradak-battery"),
        )
        assertEquals("/home/ivan", LocalHistory.decodeFolder("-home-ivan"))
    }

    @Test
    fun `an observed cwd beats the lossy folder name`() {
        session(
            "-home-me-dev-my-app",
            lines = listOf(assistant("2026-08-28T09:00:00Z", "/home/me/dev/my-app", output = 5)),
        )

        val stats = assertNotNull(LocalHistory.read(dir(), now, utc, noDistros))
        assertEquals(listOf("my-app"), stats.projects.map { it.name }, "the dash is part of the name")
    }
}
