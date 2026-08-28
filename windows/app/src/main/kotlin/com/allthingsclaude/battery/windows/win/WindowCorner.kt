package com.allthingsclaude.battery.windows.win

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

/**
 * Let Windows round the flyout's corners.
 *
 * The panel used to round itself, with a `clip(RoundedCornerShape(12.dp))` over
 * an opaque square window — which draws the rounded surface but does nothing
 * about the four corners outside it, so those stayed the AWT frame's default
 * background and the flyout wore white wedges. Clipping paints a shape; it does
 * not remove pixels from a window.
 *
 * The obvious repair is a transparent window, and it is the wrong one. Windows
 * 11 already has an opinion about how a flyout's corners look, it applies that
 * opinion at the compositor where it also gets the shadow and the hit region
 * right, and a user who has turned rounding off system-wide has said something
 * that a hardcoded 12 dp would override. Same argument the palette makes: this
 * file asks and does not decide.
 *
 * What has to be asked for explicitly is the rounding itself. Windows 11 rounds
 * ordinary framed windows on its own but leaves an undecorated one square —
 * measured here, not assumed: reading the attribute back on a live flyout gave
 * `DWMWCP_DEFAULT` and the window was square, and setting it rounded the window
 * immediately.
 */
object WindowCorner {

    /** `DWMWA_WINDOW_CORNER_PREFERENCE`. Windows 11 build 22000 and later. */
    private const val WINDOW_CORNER_PREFERENCE = 33

    /** `DWMWCP_ROUND` — the radius the shell uses for its own flyouts. */
    private const val ROUND = 2

    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            attribute: Int,
            value: IntByReference,
            size: Int,
        ): WinNT.HRESULT
    }

    private val dwmapi: Dwmapi? by lazy {
        runCatching {
            Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    /**
     * Ask the compositor to round [window], which must already be displayable.
     *
     * Returns whether it took. A false is not worth reacting to — on Windows 10
     * the attribute simply does not exist, and the answer there is a square
     * flyout, which is what Windows 10 looks like.
     */
    fun round(window: Window): Boolean = runCatching {
        val library = dwmapi ?: return false
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val preference = IntByReference(ROUND)
        library.DwmSetWindowAttribute(hwnd, WINDOW_CORNER_PREFERENCE, preference, 4).toInt() == 0
    }.getOrDefault(false)
}
