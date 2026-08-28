package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.UsageApiError
import com.allthingsclaude.battery.core.UsageBucket
import com.allthingsclaude.battery.core.UsageResponse
import com.allthingsclaude.battery.windows.auth.CredentialLookup
import com.allthingsclaude.battery.windows.poll.PollResult
import com.allthingsclaude.battery.windows.state.AppState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long the loop waits before asking again.
 *
 * The bug this pins: the loop delayed a flat sixty seconds whatever came back,
 * so a 429 was answered exactly sixty seconds later, for ever. `core` has
 * carried the escalation since it was ported from `UsagePollingService.swift`;
 * nothing on Windows was asking it.
 */
class PollCadenceTest {

    /** An explicit directory, so nothing here goes near WSL or a real file. */
    private fun stateFor(vararg results: PollResult): AppState {
        val queue = ArrayDeque(results.toList())
        return AppState(
            poll = { queue.removeFirstOrNull() ?: results.last() },
        ).also { it.resolve("C:\\nowhere\\.claude") }
    }

    private fun ok() = PollResult.Ok(
        UsageResponse(
            fiveHour = UsageBucket(12.0, Instant.now().plusSeconds(3600)),
            sevenDay = UsageBucket(26.0, Instant.now().plusSeconds(360000)),
            sevenDayOpus = null,
            scopedWeekly = null,
            extraUsage = null,
        ),
    )

    private fun rateLimited(retryAfter: Long? = null) =
        PollResult.Failed(UsageApiError.RateLimited(retryAfter))

    @Test
    fun `a good poll stays on the base cadence`() {
        val state = stateFor(ok())
        state.refresh()
        assertEquals(AppState.POLL_SECONDS, state.nextPollSeconds)
    }

    @Test
    fun `a rate limit escalates instead of asking again in a minute`() {
        val state = stateFor(rateLimited(), rateLimited(), rateLimited())

        state.refresh()
        assertEquals(60L, state.nextPollSeconds)
        state.refresh()
        assertEquals(120L, state.nextPollSeconds)
        state.refresh()
        assertEquals(240L, state.nextPollSeconds)
    }

    @Test
    fun `a long Retry-After is honoured over the escalation`() {
        val state = stateFor(rateLimited(retryAfter = 900))
        state.refresh()
        assertEquals(900L, state.nextPollSeconds, "the server asked for longer than the backoff")
    }

    @Test
    fun `a short Retry-After cannot undercut the escalation`() {
        val state = stateFor(rateLimited(retryAfter = 1))
        state.refresh()
        assertEquals(60L, state.nextPollSeconds, "answering a 429 in one second earns another")
    }

    @Test
    fun `success clears the escalation`() {
        val state = stateFor(rateLimited(), rateLimited(), ok(), rateLimited())

        state.refresh()
        state.refresh()
        assertEquals(120L, state.nextPollSeconds)

        state.refresh()
        assertEquals(AppState.POLL_SECONDS, state.nextPollSeconds)

        state.refresh()
        assertEquals(60L, state.nextPollSeconds, "back to the first step, not the third")
    }

    /**
     * A stopped distro is not an API failure. Nothing was asked, so there is
     * nothing to be gentle about — and backing off would only slow down noticing
     * that the distro came back.
     */
    @Test
    fun `a blocked poll does not back off`() {
        val blocked = PollResult.Blocked(
            CredentialLookup.Unavailable(CredentialLookup.Reason.DISTRO_NOT_RUNNING, "Ubuntu"),
        )
        val state = stateFor(blocked, blocked)

        state.refresh()
        state.refresh()
        assertEquals(AppState.POLL_SECONDS, state.nextPollSeconds)
    }

    /** …but it does not clear one either: a rate limit outlives a distro. */
    @Test
    fun `a blocked poll does not clear the escalation`() {
        val blocked = PollResult.Blocked(
            CredentialLookup.Unavailable(CredentialLookup.Reason.DISTRO_NOT_RUNNING, "Ubuntu"),
        )
        val state = stateFor(rateLimited(), rateLimited(), blocked, rateLimited())

        state.refresh()
        state.refresh()
        state.refresh()
        assertEquals(AppState.POLL_SECONDS, state.nextPollSeconds)

        state.refresh()
        assertTrue(
            state.nextPollSeconds >= 240L,
            "the escalation resumes where it left off, not from the start",
        )
    }
}
