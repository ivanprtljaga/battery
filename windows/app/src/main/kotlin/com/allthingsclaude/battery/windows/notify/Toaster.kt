package com.allthingsclaude.battery.windows.notify

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
 * **The name on it is a packaging property, not a runtime one.** The first test
 * toast was headed "OpenJDK Platform binary" with a coffee-cup icon, and the
 * obvious repair — `SetCurrentProcessExplicitAppUserModelID` — is the wrong one.
 * Measured on a real install, three ways:
 *
 * | | header shown |
 * |:--|:--|
 * | unpackaged `java.exe` | `OpenJDK Platform binary` |
 * | + an explicit AppUserModelID | `com.allthingsclaude.battery`, the raw string |
 * | the packaged exe, no explicit ID | the app's name and its icon |
 *
 * Windows takes the attribution from the executable's `FileDescription` version
 * resource, which jpackage writes from `nativeDistributions.description` — and
 * an explicit AppUserModelID *overrides* that with an identifier no Start Menu
 * entry resolves. Setting one made the packaged build worse, not better, and
 * removing it is the whole fix. A Start Menu shortcut turned out not to be
 * involved either way: the toast is attributed correctly with none installed.
 *
 * So there is no native code here at all, and the app's name in the notification
 * centre is set in `build.gradle.kts`.
 */
object Toaster {

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
