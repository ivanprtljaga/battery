package com.allthingsclaude.battery.windows.tray

import com.allthingsclaude.battery.core.BatteryPalette
import com.allthingsclaude.battery.core.UsageLevel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage

/**
 * The tray icon, drawn.
 *
 * This is the piece with no macOS counterpart. A menu bar shows *text*, so the
 * Mac app can offer "percentage + time", "percentage only", "time only" as
 * display modes and let AppKit lay them out. The Windows notification area shows
 * a 16×16 image and nothing else, so the same modes have to become *pictures* —
 * every one of them rendered here, per poll, at whatever size the current DPI
 * asks for.
 *
 * Java2D rather than Compose: the tray wants a `BufferedImage` at an exact pixel
 * size, which is precisely what Java2D is for and what a composable is not.
 */
object TrayIconRenderer {

    /** What the icon says. The Windows analogue of the macOS display modes. */
    enum class Mode {
        /** A ring alone — the calmest, and legible at every size. */
        RING,

        /**
         * A ring with the percentage inside it, and the ring alone below
         * [PERCENT_THRESHOLD].
         *
         * The ring takes the outside of the icon and the digits get what is
         * left, which is why this is the mode that runs out of room first.
         */
        RING_WITH_PERCENT,

        /**
         * The number alone, filling the icon. Loudest, most legible, and the
         * only mode that carries a readable number at 16 px — the digits get
         * the whole box instead of the hole in the middle of a ring.
         */
        PERCENT,
    }

    /**
     * Sizes Windows actually asks for.
     *
     * 16 at 100% DPI, 20 at 125%, 24 at 150%. Rendering one size and letting the
     * shell scale it is what makes a tray icon look muddy, so each is drawn at
     * its own size from scratch.
     */
    val SIZES = listOf(16, 20, 24, 32)

    /**
     * The size at which a percentage inside a ring becomes worth drawing.
     *
     * Judged on a real notification area rather than from the PNGs: at 20 px the
     * two digits sit in about eight pixels of ring interior and anti-alias into
     * a smudge, which reads as a dirty icon rather than as a number — worse than
     * the clean ring it replaces. 24 px, which is what a 150% display asks for,
     * is where they resolve. Below this the mode degrades to [Mode.RING], and
     * anyone who wants a number at 16 px wants [Mode.PERCENT], where the digits
     * get the whole icon.
     */
    const val PERCENT_THRESHOLD = 24

    /**
     * @param utilization 0–100.
     * @param stale draws the ring hollow — the last figure is being shown, but
     *   it is no longer being refreshed (the distro went away, the network is
     *   down). Saying nothing at all would let a stale number read as current.
     */
    fun render(
        utilization: Double,
        size: Int = 16,
        mode: Mode = Mode.RING,
        stale: Boolean = false,
    ): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val level = UsageLevel.from(utilization)
        val color = Color(level.color, true)

        when (mode) {
            Mode.PERCENT -> drawPercent(g, size, utilization, color)
            Mode.RING -> drawRing(g, size, utilization, color, stale)
            Mode.RING_WITH_PERCENT -> {
                drawRing(g, size, utilization, color, stale)
                if (size >= PERCENT_THRESHOLD) drawPercent(g, size, utilization, color, inset = true)
            }
        }

        g.dispose()
        return image
    }

    private fun drawRing(
        g: java.awt.Graphics2D,
        size: Int,
        utilization: Double,
        color: Color,
        stale: Boolean,
    ) {
        // Scale the stroke with the icon rather than fixing it: a 2 px ring that
        // reads well at 16 looks like a hairline at 32.
        val stroke = (size / 8f).coerceAtLeast(2f)
        val inset = stroke / 2f + 0.5f
        val diameter = size - inset * 2

        g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        // The track. Faint, and always a full circle, so an empty ring still
        // reads as a gauge rather than as a rendering failure.
        g.color = Color(color.red, color.green, color.blue, 60)
        g.draw(Arc2D.Float(inset, inset, diameter, diameter, 0f, 360f, Arc2D.OPEN))

        if (utilization <= 0) return

        // Clockwise from twelve o'clock, which is what every other Battery
        // surface does. Java2D measures angles counter-clockwise from three, so
        // the start is 90 and the extent is negated.
        val sweep = (utilization.coerceIn(0.0, 100.0) / 100.0 * 360.0).toFloat()
        g.color = if (stale) Color(color.red, color.green, color.blue, 130) else color
        g.draw(Arc2D.Float(inset, inset, diameter, diameter, 90f, -sweep, Arc2D.OPEN))
    }

    private fun drawPercent(
        g: java.awt.Graphics2D,
        size: Int,
        utilization: Double,
        color: Color,
        inset: Boolean = false,
    ) {
        // 100 would not fit, and "100" on a full gauge is the least surprising
        // number in the world — so it degrades to "99" territory by clamping the
        // label rather than shrinking the font for one edge case.
        val text = utilization.coerceIn(0.0, 99.0).toInt().toString()
        val fraction = if (inset) 0.42f else 0.72f
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (size * fraction).toInt().coerceAtLeast(6))
        g.color = color
        val metrics = g.fontMetrics
        val x = (size - metrics.stringWidth(text)) / 2f
        val y = (size - metrics.height) / 2f + metrics.ascent
        g.drawString(text, x, y)
    }
}
