package com.allthingsclaude.battery.windows.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allthingsclaude.battery.core.SessionHistory
import com.allthingsclaude.battery.core.InMemorySnapshotStore
import com.allthingsclaude.battery.core.PollBackoff
import com.allthingsclaude.battery.core.SessionPolicy
import com.allthingsclaude.battery.core.UsageApiError
import com.allthingsclaude.battery.core.UsageBucket
import com.allthingsclaude.battery.windows.auth.DirSelection
import com.allthingsclaude.battery.windows.auth.TokenCache
import com.allthingsclaude.battery.windows.config.ClaudeConfigDir
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import com.allthingsclaude.battery.windows.config.SourcePreference
import com.allthingsclaude.battery.windows.history.DayUsage
import com.allthingsclaude.battery.windows.history.LocalHistory
import com.allthingsclaude.battery.windows.history.LocalStats
import com.allthingsclaude.battery.windows.history.ProjectUsage
import com.allthingsclaude.battery.windows.poll.PollResult
import com.allthingsclaude.battery.windows.poll.UsagePoller
import com.allthingsclaude.battery.windows.wsl.Wsl
import java.io.File
import java.time.Instant
import java.time.LocalDate

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
    // A function rather than the poller itself, the same seam DirSelection draws
    // for credential reads: it is what lets the cadence below be tested without
    // an account, a network, or a clock.
    private val poll: (ClaudeDir) -> PollResult = UsagePoller(cache = TokenCache())::poll,
    // The two halves of discovery, separately, because the memo below needs the
    // cheap one to decide whether to pay for the expensive one — and because a
    // test of the poll cadence has no business starting a subprocess.
    private val runningDistros: () -> List<String> = { Wsl.running() },
    private val candidates: () -> List<ClaudeDir> = { ClaudeConfigDir.candidates() },
    private val preferenceFile: File = SourcePreference.defaultFile(),
    private val history: (ClaudeDir) -> LocalStats? = { LocalHistory.read(it) },
    private val burnRate: SessionHistory = SessionHistory(InMemorySnapshotStore()),
) {

    /**
     * `core`'s backoff, which this app previously did not use.
     *
     * The loop delayed a flat sixty seconds whatever the poll returned, so a 429
     * was answered exactly sixty seconds later, for ever — which is not how you
     * stop being rate limited. `PollBackoff` is already the port of
     * `UsagePollingService.swift`'s escalation and already honours `Retry-After`;
     * the bug was that nothing here asked it anything.
     */
    private val backoff = PollBackoff()

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

    /**
     * How long the poll loop should wait before asking again.
     *
     * Sixty seconds normally, and longer while the API is refusing. Held as
     * state rather than returned from [refresh] so that a manual "Refresh now"
     * cannot quietly reset the loop's pacing to zero.
     */
    var nextPollSeconds by mutableStateOf(POLL_SECONDS)
        private set

    /**
     * Every directory the user could switch to, for the tray's Source menu.
     *
     * Recomputed only when the set of running distros changes, because building
     * it costs a `whoami` inside each running distro and the answer does not
     * move while nothing starts or stops. The probe that guards it is the cheap
     * one, and it is the one now demonstrated not to start anything.
     */
    var sources by mutableStateOf<List<ClaudeDir>>(emptyList())
        private set

    private var lastRunning: List<String>? = null

    /**
     * The seven-day chart and the project breakdown, or null when they have not
     * been read — which is the state behind a closed panel, and the state when
     * the distro is down.
     *
     * Null rather than empty on purpose: "Ubuntu isn't running" and "you did
     * nothing all week" are different statements, and only one of them should
     * draw a flat chart.
     */
    var stats by mutableStateOf<LocalStats?>(null)
        private set

    /**
     * Whether a Claude Code session is going right now.
     *
     * Inferred from the newest transcript write rather than from a hook, so
     * there is nothing to install and it works on both installs at once. The
     * threshold is `core`'s [com.allthingsclaude.battery.core.SessionPolicy.END_GRACE_SECONDS]
     * — the shared definition of a session having ended — rather than a fresh
     * number: a tighter one would blink off during a code review, and the other
     * platforms already settled this argument.
     */
    val sessionActive: Boolean
        get() = stats?.lastActivity?.let {
            it.isAfter(Instant.now().minusSeconds(SessionPolicy.END_GRACE_SECONDS))
        } ?: false

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
        refreshSources()
        dir = when {
            explicit != null -> ClaudeDir(ClaudeConfigDir.normalize(explicit), DirOrigin.EXPLICIT)
            // A pin is honoured even when it is not among today's candidates —
            // a shut-down distro should say so, not hand the question to the
            // other install and answer with a different session window under a
            // label nobody chose. The stored origin keeps the gate that makes
            // saying so free. See SourcePreference.
            else -> SourcePreference.load(preferenceFile)
                // Not `.first()`: the cheapest candidate is not necessarily the
                // one that can answer. See DirSelection.
                ?: DirSelection.pick(sources)
        }
        identity = dir?.let { readIdentity(it) }
        if (dir == null) message = "No Claude Code directory found"
    }

    /**
     * Pin [source] and poll it straight away.
     *
     * The figures are cleared rather than left dimmed, which is the opposite of
     * what a failed poll does — and deliberately so. A failed poll leaves the
     * last reading standing because it is still the best answer *about the same
     * install*; switching source makes it an answer about a different one, and
     * showing it under the new label would be the misleading choice rather than
     * the honest one.
     *
     * Safe to call from a background thread, like [refresh], and it should be:
     * this reads a credential and then talks to the network.
     */
    fun select(source: ClaudeDir) {
        SourcePreference.save(source, preferenceFile)
        dir = source
        identity = readIdentity(source)
        usage = null
        stats = null
        message = null
        healthy = false
        lastUpdated = null
        refresh()
        loadHistory()
    }

    /**
     * Read the local transcripts. Called when the panel opens, never on a timer.
     *
     * This is the one rule the macOS app does not need: a heat map recomputed
     * behind a closed window costs a Mac some CPU and costs this app a 9P round
     * trip into a virtual machine. Safe to call from a background thread, and it
     * should be — it reads megabytes across a network redirector.
     */
    fun loadHistory() {
        val target = dir ?: return
        stats = runCatching { history(target) }.getOrNull()
    }

    /**
     * Rebuild [sources] when, and only when, the running distros have changed.
     */
    private fun refreshSources() {
        val running = runCatching { runningDistros() }.getOrDefault(emptyList())
        if (running == lastRunning && sources.isNotEmpty()) return
        lastRunning = running
        sources = runCatching { candidates() }.getOrDefault(emptyList())
    }

    private fun readIdentity(dir: ClaudeDir): String? =
        runCatching {
            File(ClaudeConfigDir.onThisPlatform(ClaudeConfigDir.configFile(dir.path))).readText()
        }.getOrNull()
            ?.let { ClaudeConfigDir.identity(it) }
            ?.let { it.email ?: it.accountUuid }

    companion object {
        /**
         * 60 seconds, matching macOS rather than Android's 180.
         *
         * The Android cadence is a battery decision — there, a poll is what keeps
         * a foreground service alive. A desktop is already awake, and the tray
         * icon is a number people glance at while working, so the tighter loop is
         * free here in a way it is not on a phone.
         */
        const val POLL_SECONDS = 60L

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
            stats = LocalStats(
                days = listOf(180_000L, 940_000L, 520_000L, 2_100_000L, 1_400_000L, 0L, 760_000L)
                    .mapIndexed { index, tokens ->
                        DayUsage(LocalDate.now().minusDays((6 - index).toLong()), tokens)
                    },
                projects = listOf(
                    ProjectUsage("battery", 3_600_000L),
                    ProjectUsage("dotfiles", 1_240_000L),
                    ProjectUsage("scratch", 88_000L),
                ),
                lastActivity = Instant.now().minusSeconds(90),
            )
        }
    }

    /** One poll. Safe to call from a background thread; state writes are cheap. */
    fun refresh() {
        refreshSources()
        val target = dir ?: return
        when (val result = poll(target)) {
            is PollResult.Ok -> {
                val response = result.usage
                val projection = response.fiveHour?.let {
                    burnRate.record(it.utilization, it.resetsAt)
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
                backoff.recordSuccess()
                nextPollSeconds = POLL_SECONDS
            }
            // A blocked or failed poll never clears `usage`. The distro shutting
            // down does not make the last reading untrue, and blanking the panel
            // for a routine, self-healing state would be the more misleading
            // choice — so the figures stay and the status dot goes grey.
            //
            // Blocked does not escalate: nothing was asked of the API, so there
            // is nothing to be gentle about. It is also the state a stopped
            // distro sits in, and backing off there would only slow down noticing
            // that it came back. The escalation is not *cleared* either — a
            // rate limit outlives a distro going away and coming back.
            is PollResult.Blocked -> {
                message = result.lookup.message
                healthy = false
                nextPollSeconds = POLL_SECONDS
            }
            is PollResult.Failed -> {
                message = result.error.message
                healthy = false
                // The server's own instruction when it sent one. PollBackoff
                // takes the larger of that and its own escalation, so an
                // optimistic Retry-After cannot undercut it.
                val retryAfter = (result.error as? UsageApiError.RateLimited)?.retryAfterSeconds
                nextPollSeconds = backoff.recordFailure(retryAfter)
            }
        }
    }
}
