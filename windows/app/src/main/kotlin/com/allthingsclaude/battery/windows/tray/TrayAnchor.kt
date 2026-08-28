package com.allthingsclaude.battery.windows.tray

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Dimension
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

/**
 * Where the notification-area icon actually is, and therefore where the flyout
 * belongs.
 *
 * Anchoring to the bottom-right corner of the screen is a guess that is wrong
 * more often than it looks. The taskbar can be at the top or on either side;
 * Windows 11 centres it by default, so "bottom right" is not even where the
 * user's eye is; and an icon that has not been pinned lives behind the overflow
 * chevron, which is somewhere else again. Windows answers the question
 * directly — `Shell_NotifyIconGetRect` returns the screen rectangle of one
 * specific icon, and the chevron's rectangle when that icon is hidden inside
 * the overflow — so guessing is not necessary.
 *
 * The awkward part is naming *our* icon. The call identifies an icon by the
 * `(hWnd, uID)` pair its owner passed to `Shell_NotifyIcon`, and AWT keeps both
 * to itself: `java.awt.TrayIcon` exposes no handle. So they are recovered from
 * outside. AWT's icon owner is a top-level window of class `SunAwtTrayIcon` in
 * this process, and its `uID` is found by asking the shell about the first few
 * candidates and keeping the one it recognises. Checked against a running app,
 * where the recovered rectangle matched the icon's measured position on screen.
 */
object TrayAnchor {

    /** AWT's window class for the hidden window that owns a tray icon. */
    private const val OWNER_CLASS = "SunAwtTrayIcon"

    /**
     * `uID` values to ask the shell about.
     *
     * AWT numbers its icons from one, but nothing documents that, so a handful
     * are offered and the shell arbitrates: it answers `S_OK` for an icon it
     * knows and an error for everything else. That makes this a lookup rather
     * than a guess.
     */
    private val CANDIDATE_IDS = 0..3

    /** Device-independent pixels between the flyout and the taskbar. */
    private const val GAP = 8

    private interface Shell32 : StdCallLibrary {
        fun Shell_NotifyIconGetRect(
            identifier: NotifyIconIdentifier,
            iconLocation: WinDef.RECT,
        ): WinNT.HRESULT
    }

    /**
     * `NOTIFYICONIDENTIFIER`, 40 bytes on x64.
     *
     * `cbSize` is filled from the struct the platform lays out rather than from
     * a literal, because getting it wrong is the one way this call fails
     * quietly instead of loudly.
     */
    @Structure.FieldOrder("cbSize", "hWnd", "uID", "guidItem")
    class NotifyIconIdentifier : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var hWnd: WinDef.HWND? = null
        @JvmField var uID: Int = 0
        @JvmField var guidItem: Guid.GUID = Guid.GUID()
    }

    private val shell32: Shell32? by lazy {
        runCatching {
            Native.load("shell32", Shell32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull()
    }

    /**
     * The icon's rectangle in **physical device pixels**, or null when this is
     * not Windows, the icon is not registered yet, or the shell declines.
     */
    fun iconRect(): Rectangle? {
        val shell = shell32 ?: return null
        val user32 = runCatching { User32.INSTANCE }.getOrNull() ?: return null
        val pid = runCatching { Kernel32.INSTANCE.GetCurrentProcessId() }.getOrNull() ?: return null

        val owners = mutableListOf<WinDef.HWND>()
        val enumerated = runCatching {
            user32.EnumWindows({ hwnd, _ ->
                val owner = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, owner)
                if (owner.value == pid) {
                    val name = CharArray(256)
                    user32.GetClassName(hwnd, name, name.size)
                    if (Native.toString(name) == OWNER_CLASS) owners.add(hwnd)
                }
                true
            }, null)
        }.isSuccess
        if (!enumerated) return null

        for (owner in owners) {
            for (id in CANDIDATE_IDS) {
                val identifier = NotifyIconIdentifier().apply {
                    hWnd = owner
                    uID = id
                    cbSize = size()
                }
                val rect = WinDef.RECT()
                val hr = runCatching { shell.Shell_NotifyIconGetRect(identifier, rect) }.getOrNull()
                if (hr == null || hr.toInt() != 0) continue

                val width = rect.right - rect.left
                val height = rect.bottom - rect.top
                // S_OK with an empty rectangle happens mid-relayout, and an
                // empty anchor is worse than no anchor: it would park the flyout
                // in a corner with no explanation.
                if (width > 0 && height > 0) return Rectangle(rect.left, rect.top, width, height)
            }
        }
        return null
    }

    /**
     * The top-left corner for a flyout of [size], in the coordinate space AWT's
     * `setLocation` speaks, or null when the icon cannot be located.
     */
    fun flyoutOrigin(size: Dimension): Point? {
        val physical = iconRect() ?: return null
        val device = deviceContaining(physical) ?: return null
        val configuration = device.defaultConfiguration
        val scale = configuration.defaultTransform.scaleX
        if (scale <= 0) return null

        val bounds = configuration.bounds
        val insets = runCatching {
            Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        }.getOrNull()
        val workArea = if (insets == null) bounds else Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
        return place(physical.scaledBy(1.0 / scale), workArea, size, GAP)
    }

    /**
     * The geometry, with no Windows in it so that it can be tested.
     *
     * Horizontally centred on the icon — the icon is the thing the user
     * clicked, and on a Windows 11 taskbar it is nowhere near a screen corner —
     * then clamped into the work area so the flyout never runs off an edge.
     * Vertically it sits on whichever side of the icon the work area is, which
     * is what makes a top or side taskbar work without a special case.
     */
    internal fun place(icon: Rectangle, workArea: Rectangle, size: Dimension, gap: Int): Point {
        val above = icon.centerY >= workArea.centerY
        val y = if (above) icon.y - gap - size.height else icon.y + icon.height + gap
        val x = (icon.centerX - size.width / 2.0).roundToInt()
        return Point(
            x.coerceIn(workArea.x, maxOf(workArea.x, workArea.x + workArea.width - size.width)),
            y.coerceIn(workArea.y, maxOf(workArea.y, workArea.y + workArea.height - size.height)),
        )
    }

    /**
     * The screen the icon is on.
     *
     * Every device is asked to read the physical rectangle with its own scale
     * factor and say whether the result lands inside its bounds. That is exact
     * for a monitor whose desktop origin is the origin — always true of the
     * primary one, which is where a taskbar with a notification area is unless
     * the user has deliberately moved it — and falls back to the primary screen
     * otherwise.
     */
    private fun deviceContaining(physical: Rectangle): GraphicsDevice? {
        val environment = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment() }
            .getOrNull() ?: return null
        val devices = runCatching { environment.screenDevices }.getOrNull() ?: return null
        for (device in devices) {
            val scale = device.defaultConfiguration.defaultTransform.scaleX
            if (scale <= 0) continue
            val scaled = physical.scaledBy(1.0 / scale)
            if (device.defaultConfiguration.bounds.contains(scaled.centerX, scaled.centerY)) {
                return device
            }
        }
        return runCatching { environment.defaultScreenDevice }.getOrNull()
    }

    private fun Rectangle.scaledBy(factor: Double) = Rectangle(
        (x * factor).roundToInt(),
        (y * factor).roundToInt(),
        (width * factor).roundToInt(),
        (height * factor).roundToInt(),
    )
}
