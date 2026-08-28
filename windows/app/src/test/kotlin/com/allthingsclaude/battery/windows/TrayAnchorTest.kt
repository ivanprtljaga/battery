package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.tray.TrayAnchor
import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flyout geometry.
 *
 * `Shell_NotifyIconGetRect` itself cannot be tested off Windows, and does not
 * need to be — it either names a rectangle or it does not. What is worth pinning
 * is what happens to that rectangle afterwards, because every one of these cases
 * is a real taskbar someone runs.
 */
class TrayAnchorTest {

    private val panel = Dimension(360, 240)
    private val gap = 8

    /** A 1920x1080 screen with a 48 px taskbar along the bottom. */
    private val bottomWorkArea = Rectangle(0, 0, 1920, 1032)

    @Test
    fun `sits above a bottom taskbar, centred on the icon`() {
        val icon = Rectangle(1400, 1032, 32, 48)
        val at = TrayAnchor.place(icon, bottomWorkArea, panel, gap)

        assertEquals(1032 - 8 - 240, at.y, "bottom edge one gap above the taskbar")
        assertEquals(1416 - 180, at.x, "centred on the icon")
    }

    @Test
    fun `hangs below a top taskbar`() {
        val workArea = Rectangle(0, 48, 1920, 1032)
        val icon = Rectangle(1400, 0, 32, 48)
        val at = TrayAnchor.place(icon, workArea, panel, gap)

        assertEquals(48 + 8, at.y, "top edge one gap below the taskbar")
    }

    @Test
    fun `stays on screen when the icon is in the corner`() {
        // The overflow chevron of a right-hand notification area, near enough to
        // the edge that centring on it would put half the panel off the screen.
        val icon = Rectangle(1880, 1032, 32, 48)
        val at = TrayAnchor.place(icon, bottomWorkArea, panel, gap)

        assertEquals(1920 - 360, at.x, "clamped to the work area's right edge")
        assertTrue(at.x >= bottomWorkArea.x)
    }

    @Test
    fun `stays on screen when the icon is at the far left`() {
        val icon = Rectangle(4, 1032, 32, 48)
        val at = TrayAnchor.place(icon, bottomWorkArea, panel, gap)

        assertEquals(0, at.x, "clamped to the work area's left edge")
    }

    @Test
    fun `honours a work area that does not start at the origin`() {
        // The second monitor of a two-screen desktop, with its own taskbar.
        val workArea = Rectangle(1920, 0, 1920, 1032)
        val icon = Rectangle(3320, 1032, 32, 48)
        val at = TrayAnchor.place(icon, workArea, panel, gap)

        assertEquals(3336 - 180, at.x)
        assertEquals(1032 - 8 - 240, at.y)
    }

    @Test
    fun `a panel taller than the work area is pinned rather than pushed off`() {
        val tall = Dimension(360, 2000)
        val icon = Rectangle(1400, 1032, 32, 48)
        val at = TrayAnchor.place(icon, bottomWorkArea, tall, gap)

        assertEquals(bottomWorkArea.y, at.y, "top-left stays reachable")
    }
}
