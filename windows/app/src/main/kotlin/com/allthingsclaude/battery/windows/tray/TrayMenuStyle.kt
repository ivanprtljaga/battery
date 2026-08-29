package com.allthingsclaude.battery.windows.tray

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit

/**
 * Make the tray menu the size Windows draws its own.
 *
 * The menu looked cramped next to every other context menu on the machine, and
 * the first half of the reason is the one the tray icon already had: **AWT does
 * not scale for DPI.** `win.menu.font` reports `Segoe UI 12`, the 96-DPI size,
 * and AWT renders it at 12 *physical* pixels. Windows' own answer, straight from
 * `SPI_GETNONCLIENTMETRICS` on this machine at 144 DPI, is `lfMenuFont` with
 * `lfHeight = -18`. So the scale factor is the correction, exactly as it was for
 * the icon, and 18 is not a guess — it is the number the shell reports.
 *
 * The second half is that 18 is then **too big**, and that is not a
 * contradiction. `lfMenuFont` describes a *classic* Win32 menu, which is the
 * only kind AWT can draw. The menus a Windows 11 user actually sees — the
 * volume flyout, an app's own tray menu — are XAML, rounded, and use a smaller
 * type ramp with much more padding: measured on this machine, ink 16 px against
 * our 17.5, and an app in the wild at 14.
 *
 * So the size is deliberately below the classic metric, to sit with the menus
 * beside it rather than with the specification. That is a taste decision and is
 * labelled as one; the DPI scaling underneath it is not.
 *
 * Horizontal padding is the part AWT will not give at all: `java.awt.MenuItem`
 * has no insets and the peer draws with fixed margins, so a modern flyout's
 * generous left column is bought here with leading spaces in the label. Ugly,
 * visible, and the alternative is not using an AWT menu — a Compose window
 * pretending to be one, with its own check marks, submenu and keyboard
 * handling.
 */
object TrayMenuStyle {

    /**
     * How far below the classic metric to sit. 18 px becomes 15, which is
     * between the shell's own flyouts (16) and a typical app's (14).
     */
    private const val MODERN_RATIO = 0.85

    /**
     * Leading spaces, standing in for the left column a modern flyout has and a
     * classic menu does not. Three lands the text about 18 px in, which is what
     * the flyouts measure.
     */
    private const val INDENT = "   "

    /**
     * Restyle every tray menu this process owns.
     *
     * Safe to call repeatedly, and it has to be: Compose rebuilds the menu's
     * items as its state changes, and an item created after this ran would
     * otherwise come back at the peer's default size.
     */
    fun apply() {
        val font = scaledMenuFont() ?: return
        runCatching {
            SystemTray.getSystemTray().trayIcons.forEach { icon ->
                icon.popupMenu?.let { restyle(it, font) }
            }
        }
    }

    private fun restyle(menu: PopupMenu, font: Font) {
        menu.font = font
        restyleChildren(menu, font)
    }

    /**
     * AWT's separator is not a kind of item — it is a `MenuItem` whose label is
     * exactly `"-"`, which the peer then draws as a rule. Indenting it makes the
     * label `"   -"`, the peer stops recognising it, and the menu grows two rows
     * that say "-". Seen in the menu, having made it happen.
     */
    private const val SEPARATOR = "-"

    private fun restyleChildren(menu: Menu, font: Font) {
        for (index in 0 until menu.itemCount) {
            val item: MenuItem = menu.getItem(index)
            item.font = font
            if (item.label == SEPARATOR) continue
            // Idempotent: this runs on every recomposition, and a label that
            // already carries the indent must not collect another one.
            if (!item.label.startsWith(INDENT)) item.label = INDENT + item.label
            if (item is Menu) restyleChildren(item, font)
        }
    }

    /**
     * The system menu font at this display's scale, or null when neither can be
     * had — in which case leaving the peer's default alone is the right answer.
     */
    private fun scaledMenuFont(): Font? = runCatching {
        val base = Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.font") as? Font
            ?: return null
        val scale = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        base.deriveFont((base.size2D * maxOf(scale, 1.0) * MODERN_RATIO).toFloat())
    }.getOrNull()
}
