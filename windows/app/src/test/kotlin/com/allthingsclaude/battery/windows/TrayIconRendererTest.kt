package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.BatteryPalette
import com.allthingsclaude.battery.windows.tray.TrayIconRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tray icon is the one surface with no macOS counterpart to compare against,
 * so what it draws is pinned here rather than eyeballed.
 */
class TrayIconRendererTest {

    private fun opaquePixels(image: java.awt.image.BufferedImage): Int {
        var count = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                if ((image.getRGB(x, y) ushr 24) != 0) count++
            }
        }
        return count
    }

    @Test
    fun `renders at every size Windows asks for`() {
        for (size in TrayIconRenderer.SIZES) {
            val image = TrayIconRenderer.render(50.0, size)
            assertEquals(size, image.width)
            assertEquals(size, image.height)
        }
    }

    /**
     * A gauge at 0% must still look like a gauge. Drawing nothing would be
     * indistinguishable from the icon having failed to load.
     */
    @Test
    fun `an empty gauge still draws its track`() {
        assertTrue(opaquePixels(TrayIconRenderer.render(0.0, 32)) > 0)
    }

    /**
     * More usage, more ink — counted by *alpha*, not by presence: the arc is stroked over the track at
     * identical geometry, so a fuller gauge lights no new pixels — it makes
     * existing ones opaque. The track is drawn at alpha 60, the arc at 255.
     */
    private fun brightPixels(image: java.awt.image.BufferedImage): Int {
        var count = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                if ((image.getRGB(x, y) ushr 24) > 200) count++
            }
        }
        return count
    }

    @Test
    fun `a fuller gauge draws more than an emptier one`() {
        val low = brightPixels(TrayIconRenderer.render(10.0, 32))
        val high = brightPixels(TrayIconRenderer.render(90.0, 32))
        assertTrue(high > low, "90% lit $high pixels, 10% lit $low")
    }

    /**
     * Severity is carried by colour, and the colour comes from `core` — the same
     * ramp the Mac, the phone and the watch faces use. A tray icon inventing its
     * own red would be a fourth opinion about what "critical" looks like.
     */
    @Test
    fun `severity colour comes from the shared palette`() {
        val calm = TrayIconRenderer.render(10.0, 32)
        val critical = TrayIconRenderer.render(95.0, 32)
        assertTrue(usesColor(calm, BatteryPalette.BRAND))
        assertTrue(usesColor(critical, BatteryPalette.BRAND_DEEP))
    }

    private fun usesColor(image: java.awt.image.BufferedImage, argb: Int): Boolean {
        val target = argb and 0xFFFFFF
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val pixel = image.getRGB(x, y)
                if ((pixel ushr 24) > 200 && (pixel and 0xFFFFFF) == target) return true
            }
        }
        return false
    }

    /**
     * Stale draws the same shape, more faintly — never a blank icon. A reading
     * that has stopped refreshing is still the best answer available, so it is
     * marked old rather than hidden.
     */
    @Test
    fun `stale is dimmer but still present`() {
        val fresh = TrayIconRenderer.render(60.0, 32, stale = false)
        val stale = TrayIconRenderer.render(60.0, 32, stale = true)
        assertEquals(opaquePixels(fresh), opaquePixels(stale), "same shape")
        assertTrue(brightPixels(stale) < brightPixels(fresh), "stale must be dimmer")
        assertTrue(opaquePixels(stale) > 0, "and never blank")
    }

    /**
     * Neither 16 nor 20 px can hold a ring and two digits at once, so the
     * combined mode degrades to the ring alone rather than drawing a smudge.
     * 20 is in here because it used to be the threshold and looked wrong on a
     * real taskbar.
     */
    @Test
    fun `ring with percent drops the number below the threshold`() {
        for (size in TrayIconRenderer.SIZES.filter { it < TrayIconRenderer.PERCENT_THRESHOLD }) {
            val ringOnly = TrayIconRenderer.render(60.0, size, TrayIconRenderer.Mode.RING)
            val combined = TrayIconRenderer.render(60.0, size, TrayIconRenderer.Mode.RING_WITH_PERCENT)
            assertEquals(opaquePixels(ringOnly), opaquePixels(combined), "at $size px")
        }
    }

    /** …and keeps it once there is room. */
    @Test
    fun `ring with percent keeps the number at the threshold and up`() {
        for (size in TrayIconRenderer.SIZES.filter { it >= TrayIconRenderer.PERCENT_THRESHOLD }) {
            val ringOnly = TrayIconRenderer.render(60.0, size, TrayIconRenderer.Mode.RING)
            val combined = TrayIconRenderer.render(60.0, size, TrayIconRenderer.Mode.RING_WITH_PERCENT)
            assertTrue(opaquePixels(combined) > opaquePixels(ringOnly), "at $size px")
        }
    }

    /** 100% would not fit in a 16 px box; the label clamps rather than overflow. */
    @Test
    fun `a full gauge still renders`() {
        val image = TrayIconRenderer.render(100.0, 16, TrayIconRenderer.Mode.PERCENT)
        assertTrue(opaquePixels(image) > 0)
    }
}
