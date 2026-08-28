package com.allthingsclaude.battery.windows.win

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * Keep the flyout out of the taskbar.
 *
 * This phase was planned as an `ITaskbarList3` overlay icon — a badge on the
 * app's taskbar button. Looking at a real taskbar inverted the premise. The
 * flyout *does* get a button, wearing the Java Duke icon, and it should not have
 * one at all: it is a popup that appears when you click the tray icon and
 * vanishes when you click away, so the button flickers in and out beside a tray
 * icon that is already this app's presence there. There is nothing to badge,
 * and a badge on a button that should not exist is not an improvement.
 *
 * Windows gives a top-level window a taskbar button unless it is owned or
 * carries `WS_EX_TOOLWINDOW`. Compose's undecorated window has neither
 * (measured: `exStyle 0x00000008`, no owner), so the flag is set here.
 *
 * `WS_EX_TOOLWINDOW` also takes the window out of Alt-Tab, which for a tray
 * flyout is the same correct answer to the same question.
 */
object TaskbarButton {

    /**
     * `WS_EX_TOOLWINDOW`, spelled out because `jna-platform` does not carry it —
     * `WinUser` defines `WS_EX_COMPOSITED`, `WS_EX_LAYERED` and
     * `WS_EX_TRANSPARENT`, and stops there.
     */
    private const val WS_EX_TOOLWINDOW = 0x00000080

    /**
     * Hide [window] from the taskbar. It must already be displayable.
     *
     * The style has to be applied while the window is hidden for the shell to
     * notice, which is why this runs before the panel is first shown and is
     * harmless afterwards. A failure costs a spurious taskbar button, so it is
     * reported rather than thrown.
     */
    fun hide(window: Window): Boolean = runCatching {
        val user32 = User32.INSTANCE
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val current = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        if (current and WS_EX_TOOLWINDOW != 0) return true

        val visible = window.isVisible
        if (visible) window.isVisible = false
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, current or WS_EX_TOOLWINDOW)
        if (visible) window.isVisible = true
        true
    }.getOrDefault(false)
}
