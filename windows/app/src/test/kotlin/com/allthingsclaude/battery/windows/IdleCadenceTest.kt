package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.UsageBucket
import com.allthingsclaude.battery.core.UsageResponse
import com.allthingsclaude.battery.windows.poll.PollResult
import com.allthingsclaude.battery.windows.state.AppState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Polling slower while nothing is happening — macOS's `pollIntervalIdle`.
 *
 * The signal is the figure, not the transcripts. `sessionActive` would be the
 * obvious choice and is the wrong one: it is read when the panel opens, so
 * behind a closed window it is always false and would call a working user idle.
 */
class IdleCadenceTest {

    private fun ok(utilization: Double) = PollResult.Ok(
        UsageResponse(
            fiveHour = UsageBucket(utilization, Instant.now().plusSeconds(3600)),
            sevenDay = UsageBucket(20.0, Instant.now().plusSeconds(360000)),
            sevenDayOpus = null,
            scopedWeekly = null,
            extraUsage = null,
        ),
    )

    private fun stateFor(vararg results: PollResult): AppState {
        val queue = ArrayDeque(results.toList())
        return AppState(
            poll = { queue.removeFirstOrNull() ?: results.last() },
            runningDistros = { emptyList() },
            candidates = { emptyList() },
        ).also { it.resolve("C:\\nowhere\\.claude") }
    }

    /** The arithmetic on its own, so the steps are pinned without a poll loop. */
    @Test
    fun `the cadence steps up and then stops`() {
        assertEquals(60L, AppState.idleCadence(0))
        assertEquals(60L, AppState.idleCadence(1))
        assertEquals(60L, AppState.idleCadence(2), "a pause to read a diff costs nothing")
        assertEquals(120L, AppState.idleCadence(3))
        assertEquals(180L, AppState.idleCadence(4))
        assertEquals(300L, AppState.idleCadence(6))
        assertEquals(300L, AppState.idleCadence(50), "and never further")
    }

    @Test
    fun `an unmoving figure slows the loop down`() {
        val state = stateFor(ok(30.0), ok(30.0), ok(30.0), ok(30.0), ok(30.0))

        repeat(3) { state.refresh() }
        assertEquals(60L, state.nextPollSeconds, "three identical readings is still working")

        state.refresh()
        assertEquals(120L, state.nextPollSeconds)
        state.refresh()
        assertEquals(180L, state.nextPollSeconds)
    }

    /** The half that matters: noticing work start must not take five minutes. */
    @Test
    fun `any change snaps straight back to the base rate`() {
        val state = stateFor(ok(30.0), ok(30.0), ok(30.0), ok(30.0), ok(30.0), ok(31.0))

        repeat(5) { state.refresh() }
        assertTrue(state.nextPollSeconds > 60L, "idle by now")

        state.refresh()
        assertEquals(60L, state.nextPollSeconds, "one changed figure and it is awake")
    }

    /**
     * A window rolling over is a change like any other, and the one where being
     * slow would be most visible — the number falls to nearly nothing and the
     * user is most likely to look.
     */
    @Test
    fun `a reset wakes the loop`() {
        val state = stateFor(ok(90.0), ok(90.0), ok(90.0), ok(90.0), ok(90.0), ok(2.0))
        repeat(5) { state.refresh() }
        state.refresh()
        assertEquals(60L, state.nextPollSeconds)
    }
}
