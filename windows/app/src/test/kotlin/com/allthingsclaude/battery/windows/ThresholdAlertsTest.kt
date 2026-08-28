package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.notify.ThresholdAlerts
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When Battery is allowed to interrupt someone.
 *
 * The macOS original is private state on a service that calls
 * `UNUserNotificationCenter` inline, so none of this is covered there. It is
 * covered here because interrupting a person twice for the same thing is the
 * kind of bug nobody reports — they just turn notifications off.
 */
class ThresholdAlertsTest {

    private val start: Instant = Instant.parse("2026-08-28T12:00:00Z")

    @Test
    fun `nothing to say below the first threshold`() {
        val alerts = ThresholdAlerts()
        assertNull(alerts.evaluate(0.0, start))
        assertNull(alerts.evaluate(45.0, start))
        assertNull(alerts.evaluate(79.9, start))
    }

    @Test
    fun `each threshold speaks once as it is crossed`() {
        val alerts = ThresholdAlerts()

        assertEquals("Session usage at 80%", alerts.evaluate(81.0, start)?.title)
        assertEquals("Session usage at 90%", alerts.evaluate(91.0, start)?.title)
        assertEquals("Session usage at 95%", alerts.evaluate(96.0, start)?.title)
    }

    /** Once said, it stays said — not once a minute for the next four hours. */
    @Test
    fun `a crossed threshold does not repeat`() {
        val alerts = ThresholdAlerts()
        assertNotNull(alerts.evaluate(82.0, start))

        assertNull(alerts.evaluate(83.0, start.plusSeconds(60)))
        assertNull(alerts.evaluate(85.0, start.plusSeconds(120)))
        assertNull(alerts.evaluate(89.0, start.plusSeconds(180)))
    }

    /**
     * The departure from macOS. There, 78% to 91% in one poll fires 80 and 90
     * together and two toasts stack up describing the same moment.
     */
    @Test
    fun `a jump past two thresholds says only the higher one`() {
        val alerts = ThresholdAlerts()

        assertEquals("Session usage at 90%", alerts.evaluate(91.0, start)?.title)
        assertNull(alerts.evaluate(92.0, start.plusSeconds(60)), "80 was recorded, not queued")
    }

    @Test
    fun `the body carries the real figure, not the threshold`() {
        val alert = assertNotNull(ThresholdAlerts().evaluate(83.4, start))
        assertTrue(alert.body.contains("83%"), alert.body)
    }

    /** Without this the second half of a long session is silent. */
    @Test
    fun `a threshold re-arms once usage falls back below it`() {
        val alerts = ThresholdAlerts()
        assertNotNull(alerts.evaluate(81.0, start))

        assertNull(alerts.evaluate(70.0, start.plusSeconds(600)))
        assertEquals(
            "Session usage at 80%",
            alerts.evaluate(82.0, start.plusSeconds(4000))?.title,
            "it climbed back and should say so",
        )
    }

    /**
     * Re-arming alone would let a figure sitting on a boundary toast on every
     * poll. The debounce is what makes that a non-event.
     */
    @Test
    fun `a figure hovering on a boundary does not toast repeatedly`() {
        val alerts = ThresholdAlerts()
        assertNotNull(alerts.evaluate(80.2, start))

        assertNull(alerts.evaluate(79.6, start.plusSeconds(60)))
        assertNull(alerts.evaluate(80.4, start.plusSeconds(120)), "within the hour")
        assertNull(alerts.evaluate(79.5, start.plusSeconds(180)))
        assertNull(alerts.evaluate(80.1, start.plusSeconds(240)))

        assertNotNull(
            alerts.evaluate(80.3, start.plus(Duration.ofHours(2))),
            "and speaks again once the hour is up",
        )
    }

    /** core's RESET_FROM / RESET_TO, so every platform agrees what a reset is. */
    @Test
    fun `a window rolling over is announced and clears the state`() {
        val alerts = ThresholdAlerts()
        assertNotNull(alerts.evaluate(96.0, start))

        val reset = assertNotNull(alerts.evaluate(4.0, start.plusSeconds(3600)))
        assertEquals("Session reset", reset.title)
        assertTrue(reset.body.contains("4%"), reset.body)

        // Everything is armed again, and the debounce went with it.
        assertEquals(
            "Session usage at 80%",
            alerts.evaluate(81.0, start.plusSeconds(3700))?.title,
        )
    }

    @Test
    fun `a gentle decline is not a reset`() {
        val alerts = ThresholdAlerts()
        alerts.evaluate(40.0, start)
        assertNull(alerts.evaluate(20.0, start.plusSeconds(60)), "never above RESET_FROM")

        val other = ThresholdAlerts()
        other.evaluate(60.0, start)
        assertNull(other.evaluate(15.0, start.plusSeconds(60)), "did not fall below RESET_TO")
    }

    @Test
    fun `the very first reading is never a reset`() {
        assertNull(ThresholdAlerts().evaluate(2.0, start), "there is no previous to have dropped from")
    }
}
