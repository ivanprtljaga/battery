package com.allthingsclaude.battery.windows.notify

import com.allthingsclaude.battery.core.SessionPolicy
import java.time.Duration
import java.time.Instant

/** One thing worth telling the user, already worded. */
data class Alert(val title: String, val body: String)

/**
 * When a session's usage is worth interrupting someone about.
 *
 * The port of `NotificationService.checkThresholds` and its neighbours, with the
 * structural change this codebase keeps making to ported logic: **it is pure.**
 * The macOS version interleaves the decision with `UNUserNotificationCenter`
 * calls and shared settings, so none of its arithmetic is covered by a test.
 * Here [evaluate] is a function of (state, utilization, now) and the caller
 * merely delivers whatever comes back.
 *
 * Three rules, all of them from the original:
 *
 * **Say it once.** A threshold that has fired stays fired. Crossing 80% does not
 * re-announce itself every sixty seconds for the next four hours.
 *
 * **Re-arm on the way down.** A threshold above the current figure is cleared,
 * so a window that drops back to 70% and climbs again will say so. That is
 * `resetThresholds(below:)`, and without it the second half of a long session is
 * silent.
 *
 * **Debounce anyway.** Re-arming alone would let a figure hovering on a boundary
 * — 79.6, 80.2, 79.8 — toast on every poll. An hour between repeats of the same
 * threshold makes that a non-event.
 */
class ThresholdAlerts(
    private val thresholds: List<Int> = DEFAULT_THRESHOLDS,
    private val debounce: Duration = DEBOUNCE,
) {
    private val notified = mutableSetOf<Int>()
    private val notifiedAt = mutableMapOf<Int, Instant>()
    private var previous: Double? = null

    /**
     * What to say about [utilization], if anything.
     *
     * At most one alert per call, which is the one deliberate departure from
     * macOS. There, a jump from 78% to 91% fires 80 and 90 together and the user
     * gets two toasts stacked describing the same moment. Here the lower
     * thresholds are marked as said without being said: the highest one carries
     * strictly more information, and the quieter reading of "tell them when it
     * matters" is the one toast.
     */
    fun evaluate(utilization: Double, now: Instant = Instant.now()): Alert? {
        val before = previous
        previous = utilization

        // A window that rolled over. core's numbers, so every platform agrees on
        // what a reset looks like rather than each carrying its own pair.
        if (before != null && before > SessionPolicy.RESET_FROM && utilization < SessionPolicy.RESET_TO) {
            notified.clear()
            notifiedAt.clear()
            return Alert(
                "Session reset",
                "Your five-hour window has rolled over. Back to ${utilization.roundedPercent()}%.",
            )
        }

        // On the way down, anything above the current figure can speak again.
        notified.removeAll { it > utilization }

        val crossed = thresholds
            .filter { utilization >= it }
            .sorted()
        if (crossed.isEmpty()) return null

        val highest = crossed.last()
        // The ones below the highest are true but not worth a separate
        // interruption; recording them keeps them from arriving one poll later.
        crossed.dropLast(1).forEach {
            notified += it
            notifiedAt[it] = now
        }

        if (highest in notified) return null
        val last = notifiedAt[highest]
        if (last != null && Duration.between(last, now) < debounce) return null

        notified += highest
        notifiedAt[highest] = now
        return Alert(
            "Session usage at $highest%",
            "Your five-hour Claude Code window is at ${utilization.roundedPercent()}%.",
        )
    }

    private fun Double.roundedPercent(): Int = Math.round(this).toInt()

    companion object {
        /** Matching `AppSettings.notifyAt80/90/95`. */
        val DEFAULT_THRESHOLDS = listOf(80, 90, 95)

        /** `NotificationService.thresholdDebounceInterval`. */
        val DEBOUNCE: Duration = Duration.ofHours(1)
    }
}
