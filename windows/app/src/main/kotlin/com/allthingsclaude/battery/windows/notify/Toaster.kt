package com.allthingsclaude.battery.windows.notify

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Windows toasts, without WinRT.
 *
 * The plan for this phase said "WinRT toasts via JNA" — `ToastNotificationManager`,
 * `RoGetActivationFactory`, HSTRING marshalling, an `IInspectable` vtable walked
 * by hand. Measured on the real machine, none of that is necessary:
 * `TrayIcon.displayMessage` produces a genuine Windows 11 toast, in the
 * notification centre, with the system's own styling and dismiss affordances.
 * Windows routes `Shell_NotifyIcon`'s `NIF_INFO` into the modern notification
 * system, and AWT already speaks it. The whole delivery mechanism is one call.
 *
 * What *is* worth a native call is the name on it. The first test toast was
 * headed **"OpenJDK Platform binary"** with a coffee-cup icon, because a toast
 * is attributed to its process's AppUserModelID and an unregistered `java.exe`
 * has the JDK's. [claimIdentity] replaces it.
 *
 * That gets the branding off and not much more: with no Start Menu shortcut
 * carrying the same ID, Windows has no display name or icon to resolve and shows
 * the raw string — verified, `com.allthingsclaude.battery` in place of the JDK.
 * The rest is a packaging problem, not a notification one: jpackage's
 * `--win-menu` writes exactly that shortcut, so the friendly name and the
 * terracotta icon arrive with Phase 5 rather than with more Win32.
 */
object Toaster {

    /** Reverse-DNS, matching the bundle identifiers the other platforms use. */
    const val APP_ID = "com.allthingsclaude.battery"

    private interface Shell32 : StdCallLibrary {
        fun SetCurrentProcessExplicitAppUserModelID(appId: WString): Int
    }

    /**
     * Tell Windows who this process is, before the first toast.
     *
     * Idempotent from the caller's side and safe to fail: a toast headed with
     * the wrong name is worse than one headed with the right one, and both are
     * better than no toast.
     */
    fun claimIdentity(): Boolean = runCatching {
        Native.load("shell32", Shell32::class.java)
            .SetCurrentProcessExplicitAppUserModelID(WString(APP_ID)) == 0
    }.getOrDefault(false)

    /**
     * Show [alert], or do nothing when there is no icon to hang it on.
     *
     * The tray icon is reached through `SystemTray.trayIcons` because Compose
     * owns it and exposes no handle — the same route the single-click listener
     * takes.
     */
    fun show(alert: Alert, type: TrayIcon.MessageType = TrayIcon.MessageType.WARNING): Boolean =
        runCatching {
            val icon = SystemTray.getSystemTray().trayIcons.firstOrNull() ?: return false
            icon.displayMessage(alert.title, alert.body, type)
            true
        }.getOrDefault(false)
}
