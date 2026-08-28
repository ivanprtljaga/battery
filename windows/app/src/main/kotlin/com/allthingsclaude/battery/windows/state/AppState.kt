package com.allthingsclaude.battery.windows.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allthingsclaude.battery.core.SessionHistory
import com.allthingsclaude.battery.core.InMemorySnapshotStore
import com.allthingsclaude.battery.core.UsageBucket
import com.allthingsclaude.battery.windows.auth.DirSelection
import com.allthingsclaude.battery.windows.auth.TokenCache
import com.allthingsclaude.battery.windows.config.ClaudeConfigDir
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.poll.PollResult
import com.allthingsclaude.battery.windows.poll.UsagePoller
import java.io.File
import java.time.Instant

/** Everything the panel and the tray draw from. */
data class UiUsage(
    val session: UsageBucket?,
    val weekly: UsageBucket,
    val scoped: UsageBucket?,
    val scopedLabel: String?,
    val projection: String?,
    val extra: String?,
) {
    /**
     * What the tray icon shows.
     *
     * The session window when there is one, because that is the limit anyone is
     * actually pacing against minute to minute; the weekly only when no session
     * window is open.
     */
    val headline: Double get() = session?.utilization ?: weekly.utilization
}

/**
 * The single place the poll loop's output becomes something to draw.
 *
 * Held together by plain `mutableStateOf` rather than a ViewModel: there is one
 * screen, one loop and one process, and the indirection would describe a
 * structure this app does not have.
 */
class AppState(
    private val poller: UsagePoller = UsagePoller(cache = TokenCache()),
    private val history: SessionHistory = SessionHistory(InMemorySnapshotStore()),
) {
    var dir by mutableStateOf<ClaudeDir?>(null)
        private set
    var usage by mutableStateOf<UiUsage?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var identity by mutableStateOf<String?>(null)
        private set
    var healthy by mutableStateOf(false)
        private set
    var lastUpdated by mutableStateOf<Instant?>(null)
        private set

    /** "WSL: Ubuntu", or the reason there is nothing to read. */
    val sourceLabel: String get() = dir?.label ?: "No Claude Code directory"

    /**
     * True once a reading has gone stale — shown as a hollow tray ring rather
     * than a blanked one, because the last number is still the best answer
     * available and hiding it would be less honest than marking it old.
     */
    val stale: Boolean
        get() = usage != null && !healthy

    fun resolve(explicit: String? = null) {
        dir = if (explicit != null) {
            ClaudeDir(
                ClaudeConfigDir.normalize(explicit),
                com.allthingsclaude.battery.windows.config.DirOrigin.EXPLICIT,
            )
        } else {
            // Not `.first()`: the cheapest candidate is not necessarily the one
            // that can answer. See DirSelection.
            DirSelection.pick(ClaudeConfigDir.candidates())
        }
        identity = dir?.let { readIdentity(it) }
        if (dir == null) message = "No Claude Code directory found"
    }

    private fun readIdentity(dir: ClaudeDir): String? =
        runCatching {
            File(ClaudeConfigDir.onThisPlatform(ClaudeConfigDir.configFile(dir.path))).readText()
        }.getOrNull()
            ?.let { ClaudeConfigDir.identity(it) }
            ?.let { it.email ?: it.accountUuid }

    companion object {
        /**
         * Fixed figures, for offscreen rendering when there is no account to
         * read — so a layout can be reviewed without a credential, a network, or
         * a Windows machine.
         */
        fun preview(): AppState = AppState().apply {
            identity = "someone@example.com"
            healthy = true
            usage = UiUsage(
                session = UsageBucket(63.0, Instant.now().plusSeconds(46 * 60)),
                weekly = UsageBucket(24.0, Instant.now().plusSeconds(124 * 3600)),
                scoped = UsageBucket(22.0, Instant.now().plusSeconds(124 * 3600)),
                scopedLabel = "Fable",
                projection = "8.4% per hour",
                extra = "Extra $20.80 of $40.00",
            )
        }
    }

    /** One poll. Safe to call from a background thread; state writes are cheap. */
    fun refresh() {
        val target = dir ?: return
        when (val result = poller.poll(target)) {
            is PollResult.Ok -> {
                val response = result.usage
                val projection = response.fiveHour?.let {
                    history.record(it.utilization, it.resetsAt)
                }
                usage = UiUsage(
                    session = response.fiveHour,
                    weekly = response.sevenDay,
                    scoped = response.sevenDayOpus
                        ?: response.scopedWeekly?.let { s -> UsageBucket(s.utilization, s.resetsAt) },
                    scopedLabel = response.scopedWeekly?.label ?: response.sevenDayOpus?.let { "Opus" },
                    projection = projection?.takeIf { it.ratePerHour > 0 }
                        ?.let { "%.1f%% per hour".format(it.ratePerHour) },
                    extra = response.extraUsage?.takeIf { it.isPresentable }
                        ?.let { "Extra ${it.format(it.used)} of ${it.format(it.limit)}" },
                )
                message = null
                healthy = true
                lastUpdated = Instant.now()
            }
            // A blocked or failed poll never clears `usage`. The distro shutting
            // down does not make the last reading untrue, and blanking the panel
            // for a routine, self-healing state would be the more misleading
            // choice — so the figures stay and the status dot goes grey.
            is PollResult.Blocked -> {
                message = result.lookup.message
                healthy = false
            }
            is PollResult.Failed -> {
                message = result.error.message
                healthy = false
            }
        }
    }
}
