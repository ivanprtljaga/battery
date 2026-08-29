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
 * the reason is the one the tray icon already had: **AWT does not scale for
 * DPI.** `win.menu.font` reports `Segoe UI 12`, which is the 96-DPI pixel size
 * of the system's 9 pt menu font, and AWT then renders it at 12 *physical*
 * pixels. On a 150% display Windows draws that same font at 18, so the app's
 * menu came out two thirds the size of the shell's — and would have come out
 * half the size at 200%.
 *
 * So this is not a taste setting. Multiplying by the screen's scale factor is
 * the same correction `trayIconSize` makes for the icon, for the same reason,
 * and the result matches a native menu rather than merely being bigger.
 *
 * The item height follows the font, which is where the vertical breathing room
 * comes from. Horizontal padding is not adjustable: `java.awt.MenuItem` has no
 * insets and the peer draws with the shell's fixed margins. Getting more would
 * mean not using an AWT menu at all — a Compose window pretending to be one,
 * with its own check marks, submenus and keyboard handling.
 */
object TrayMenuStyle {

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

    private fun restyleChildren(menu: Menu, font: Font) {
        for (index in 0 until menu.itemCount) {
            val item: MenuItem = menu.getItem(index)
            item.font = font
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
        if (scale <= 1.0) base else base.deriveFont((base.size2D * scale).toFloat())
    }.getOrNull()
}
