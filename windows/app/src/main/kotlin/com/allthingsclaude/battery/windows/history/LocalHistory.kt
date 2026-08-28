package com.allthingsclaude.battery.windows.history

import com.allthingsclaude.battery.windows.config.ClaudeConfigDir
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.wsl.RealWslCommand
import com.allthingsclaude.battery.windows.wsl.Wsl
import com.allthingsclaude.battery.windows.wsl.WslCommand
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day's tokens. */
data class DayUsage(val date: LocalDate, val tokens: Long)

/** One project's tokens over the window. */
data class ProjectUsage(val name: String, val tokens: Long)

/**
 * What the panel draws below the gauges.
 *
 * [days] is always exactly [LocalHistory.WINDOW_DAYS] long, oldest first and
 * zero-filled, so the chart never has to reason about gaps — a quiet Sunday is a
 * bar of height zero, not a missing column.
 */
data class LocalStats(
    val days: List<DayUsage>,
    val projects: List<ProjectUsage>,
    val lastActivity: Instant?,
) {
    val totalTokens: Long get() = days.sumOf { it.tokens }
    val busiestDay: Long get() = days.maxOfOrNull { it.tokens } ?: 0L
}

/**
 * Claude Code's own transcripts, read locally.
 *
 * Three facts from a real Windows machine shaped this, none of which the plan
 * assumed:
 *
 * **`stats-cache.json` is not there.** macOS treats it as the primary source and
 * the JSONL as a supplement (`StatsCacheService.swift`); on a current Claude
 * Code it does not exist on either the native or the WSL install, so here the
 * transcripts are the only source. That removes a whole tier of the macOS
 * design rather than porting it.
 *
 * **Nothing can watch these files.** `ReadDirectoryChangesW` arms without error
 * on a `\\wsl.localhost` path and then never fires — not for a write from
 * inside the distro, and not even for one from the Windows side of the same
 * share. Polling is not the fallback, it is the mechanism, and it is why this
 * is a plain function over `lastModified` rather than a watcher.
 *
 * **Reading is cheap; deciding to read is the expensive part.** 2.1 MB of
 * transcript comes back over 9P in 68 ms, and a stat pass over the tree takes
 * 8. So the shape is: stat everything, discard whatever is older than the
 * window, read only what survives.
 *
 * Called when the panel opens, never on the poll timer. Nobody needs a seven-day
 * chart recomputed behind a closed window.
 */
object LocalHistory {

    /** Days in the chart. Seven, matching the weekly gauge beside it. */
    const val WINDOW_DAYS = 7

    /** Projects in the breakdown. Beyond this a tray flyout is a report. */
    const val MAX_PROJECTS = 5

    /**
     * Every line worth parsing contains this, and two thirds of them do not.
     *
     * Only `type: assistant` lines carry `message.usage`, and they are 335 of
     * 1031 in a real tree. A substring test over the raw line skips the rest
     * without building any JSON at all, which matters because the lines that are
     * skipped include the largest ones — tool output and file snapshots.
     */
    private const val USAGE_MARKER = "\"usage\""

    /**
     * Read the last [WINDOW_DAYS] days for [dir], or null when it cannot be read
     * without consequences.
     *
     * Null rather than empty: "the distro is down" and "you did nothing all
     * week" are different statements, and only one of them should blank a chart.
     */
    fun read(
        dir: ClaudeDir,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        command: WslCommand = RealWslCommand,
    ): LocalStats? {
        // Before the path is named. See Wsl.reachable — stat-ing a
        // \\wsl.localhost path is itself what starts the distro.
        if (!Wsl.reachable(dir, command)) return null

        val projects = File(ClaudeConfigDir.onThisPlatform(dir.path), "projects")
        val files = runCatching {
            projects.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList()
        }.getOrNull() ?: return null

        val today = now.atZone(zone).toLocalDate()
        val from = today.minusDays((WINDOW_DAYS - 1).toLong())
        val cutoff = from.atStartOfDay(zone).toInstant().toEpochMilli()

        // The newest write anywhere in the tree, taken from the stat pass before
        // anything is filtered — a live session in a project that has been quiet
        // all week still counts as activity.
        val lastActivity = files.maxOfOrNull { it.lastModified() }
            ?.takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli)

        val byDay = HashMap<LocalDate, Long>()
        val byProject = HashMap<String, Long>()
        // The shallowest `cwd` seen in each transcript folder — see below.
        val rootOf = HashMap<String, String>()

        // A file last written before the window opened cannot contain a line
        // inside it, so it is never opened.
        for (file in files.filter { it.lastModified() >= cutoff }) {
            // Grouped by the transcript's folder, which is Claude Code's own
            // notion of a project, and **not** by the line's `cwd`.
            //
            // `cwd` is recorded per message and moves as the session works: one
            // real session here logged 224 messages at `…/battery`, 63 at
            // `…/battery/windows` and 3 at `…/battery/android`. Attributing per
            // line splits one project into three, ranked by where somebody
            // happened to be standing. The folder is fixed for the session.
            val folder = file.parentFile?.name ?: continue
            runCatching {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.contains(USAGE_MARKER)) continue
                        val entry = TranscriptLine.parse(line) ?: continue
                        val date = entry.timestamp.atZone(zone).toLocalDate()
                        if (date < from || date > today) continue
                        byDay[date] = (byDay[date] ?: 0L) + entry.tokens
                        byProject[folder] = (byProject[folder] ?: 0L) + entry.tokens
                        // Every drifted `cwd` is *below* the session's root, so
                        // the shallowest one seen is that root — and a real path
                        // beats decoding the folder name, which is lossy.
                        val cwd = entry.cwd
                        if (cwd != null && cwd.length < (rootOf[folder]?.length ?: Int.MAX_VALUE)) {
                            rootOf[folder] = cwd
                        }
                    }
                }
            }
        }

        return LocalStats(
            days = (0 until WINDOW_DAYS).map { offset ->
                val date = from.plusDays(offset.toLong())
                DayUsage(date, byDay[date] ?: 0L)
            },
            projects = byProject.entries
                .sortedByDescending { it.value }
                .take(MAX_PROJECTS)
                .mapNotNull { (folder, tokens) ->
                    val path = rootOf[folder] ?: decodeFolder(folder)
                    projectName(path)?.let { ProjectUsage(it, tokens) }
                },
            lastActivity = lastActivity,
        )
    }

    /**
     * Directory names that group packages inside a monorepo. A leaf under one
     * of these says nothing alone, so it is qualified with the repo.
     *
     * Ported from `StatsCacheService.monorepoContainers` rather than
     * reinvented, so a project reads the same on both platforms.
     */
    private val MONOREPO_CONTAINERS = setOf(
        "apps", "app", "packages", "package", "services", "service",
        "libs", "lib", "modules", "sites", "examples", "crates",
        "pkgs", "projects", "workspaces",
    )

    /** Leaf names too generic to identify a project alone. */
    private val GENERIC_LEAVES = setOf(
        "web", "app", "www", "site", "frontend", "backend", "server",
        "client", "api", "docs", "landing", "admin", "dashboard", "mobile",
        "desktop", "core", "common", "shared", "ui", "cli", "src",
        // Added to the macOS set, and earned on this repo: its own tree is
        // android/ ios/ windows/, and a row reading "windows" names nothing.
        "windows", "android", "ios", "linux", "macos",
    )

    /**
     * A project's display name, from a path.
     *
     * The leaf is what people call a project, except when the leaf is generic —
     * `battery/windows` says something that `windows` does not — and a package
     * under `apps/` is named by its repo. A qualifier equal to the account name
     * carries nothing, so `~/dev/battery` stays `battery`.
     *
     * The port of `StatsCacheService.displayName(forPath:)`.
     */
    internal fun projectName(raw: String?): String? {
        val parts = raw.orEmpty().split('/', '\\').filter { it.isNotBlank() }
        val leaf = parts.lastOrNull() ?: return null
        if (parts.size < 2) return leaf
        val parent = parts[parts.size - 2]

        if (parent.lowercase() in MONOREPO_CONTAINERS) {
            val repo = parts.getOrNull(parts.size - 3) ?: return leaf
            return if (isUserName(repo)) leaf else "$repo/$leaf"
        }
        if (leaf.lowercase() in GENERIC_LEAVES) {
            return if (isUserName(parent)) leaf else "$parent/$leaf"
        }
        return leaf
    }

    /**
     * The path a transcript folder encodes.
     *
     * Claude Code names the folder after the working directory with every
     * separator replaced by a dash — `-home-ivan-dev-repos-markoradak-battery`.
     * The encoding is lossy: a directory with a real dash in its name is
     * indistinguishable from a separator. That is exactly why an observed `cwd`
     * is preferred and this is only the fallback.
     */
    internal fun decodeFolder(folder: String): String =
        "/" + folder.removePrefix("-").replace('-', '/')

    /** The account name, which as a qualifier says nothing. */
    private fun isUserName(segment: String): Boolean {
        val user = System.getProperty("user.name").orEmpty()
        return user.isNotEmpty() && segment.equals(user, ignoreCase = true)
    }
}
