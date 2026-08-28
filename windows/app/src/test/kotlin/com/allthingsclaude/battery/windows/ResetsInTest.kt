package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.ui.resetsIn
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reset caption.
 *
 * The defect this pins was on screen for three phases: a seven-day window read
 * "123h 47m", which is a number nobody converts into a plan. Under a day it is
 * still a countdown, because "4h 37m" is exactly the question being asked about
 * a five-hour session.
 */
class ResetsInTest {

    /** A Wednesday, 09:00, fixed so weekday arithmetic is checkable. */
    private val now: Instant = Instant.parse("2026-08-26T09:00:00Z")
    private val utc: ZoneId = ZoneId.of("UTC")
    private val uk = Locale.UK

    private fun at(instant: String) = resetsIn(Instant.parse(instant), now, utc, uk)

    @Test
    fun `nothing to show without a reset time`() {
        assertEquals("—", resetsIn(null, now, utc, uk))
    }

    @Test
    fun `a window already past is resetting`() {
        assertEquals("resetting", at("2026-08-26T09:00:00Z"))
        assertEquals("resetting", at("2026-08-26T08:00:00Z"))
    }

    /** core's shortDuration, pinned across all four platforms by the fixtures. */
    @Test
    fun `under a day stays a countdown`() {
        assertEquals("4h 37m", at("2026-08-26T13:37:00Z"))
        assertEquals("45m", at("2026-08-26T09:45:00Z"))
        assertEquals("23h 0m", at("2026-08-27T08:00:00Z"))
    }

    /**
     * The change. Twenty-four hours is where a duration stops being an answer.
     */
    @Test
    fun `past a day it says when, not how long`() {
        // Friday, two days out.
        assertEquals("Fri 19:00", at("2026-08-28T19:00:00Z"))
        // A weekly window at its full seven days: six sleeps away, still a weekday.
        assertEquals("Tue 09:00", at("2026-09-01T09:00:00Z"))
    }

    @Test
    fun `the boundary is exactly a day`() {
        // A second short of a day is still a countdown, truncated the way
        // shortDuration truncates everywhere else.
        assertEquals("23h 59m", at("2026-08-27T08:59:59Z"))
        // A second past it is the first to name a day.
        assertEquals("Thu 09:00", at("2026-08-27T09:00:01Z"))
    }

    /**
     * Seven days out the weekday has come round again and would name two
     * different days. No plan's window is this long today; one could be.
     */
    @Test
    fun `beyond a week it falls back to a date`() {
        // Not an exact string: the month abbreviation is CLDR's and moves with
        // the JDK — en-GB says "Sept", not "Sep". The shape is what matters.
        val text = at("2026-09-05T09:00:00Z")
        assertTrue(text.startsWith("5 Sep"), text)
        assertTrue(text.endsWith("09:00"), text)
    }

    /** The clock is the viewer's, not the server's. */
    @Test
    fun `the time is rendered in the local zone`() {
        val tokyo = resetsIn(Instant.parse("2026-08-28T19:00:00Z"), now, ZoneId.of("Asia/Tokyo"), uk)
        assertEquals("Sat 04:00", tokyo, "19:00 UTC on Friday is 04:00 Saturday in Tokyo")
    }

    /** …and so is the clock format. */
    @Test
    fun `a twelve-hour locale gets a twelve-hour clock`() {
        val us = resetsIn(Instant.parse("2026-08-28T19:00:00Z"), now, utc, Locale.US)
        assertTrue(us.startsWith("Fri "), us)
        assertTrue(us.contains("7:00") && us.uppercase().contains("PM"), us)
    }
}
