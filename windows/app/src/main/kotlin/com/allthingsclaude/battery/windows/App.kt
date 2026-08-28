package com.allthingsclaude.battery.windows

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.allthingsclaude.battery.windows.dev.renderScreenshots
import com.allthingsclaude.battery.windows.state.AppState
import com.allthingsclaude.battery.windows.tray.TrayIconRenderer
import com.allthingsclaude.battery.windows.ui.BatteryTheme
import com.allthingsclaude.battery.windows.ui.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.SystemTray

/**
 * Phase 2: the tray icon and the flyout.
 *
 * ```
 * gradlew :app:run                       # tray icon; click it for the panel
 * gradlew :app:run --args="--panel"      # panel up front, for a screenshot
 * gradlew :app:run --args="--headless"   # Phase 1's console poller
 * gradlew :app:run --args="--screenshot build/shots"   # render every surface to PNG
 * ```
 */
fun main(args: Array<String>) {
    if (args.contains("--headless")) {
        runHeadless(args)
        return
    }

    val explicit = args.valueOfFlag("--dir") ?: System.getenv("BATTERY_CLAUDE_DIR")

    args.valueOfFlag("--screenshot")?.let { dir ->
        val state = if (explicit != null) {
            AppState().also { it.resolve(explicit); it.refresh() }
        } else {
            AppState.preview()
        }
        renderScreenshots(dir, state)
        return
    }
    // Tray support is absent on some desktops — notably when this is run under
    // WSLg during development. Falling back to a plain visible window keeps the
    // app usable and, more to the point, keeps the panel reviewable somewhere
    // other than Windows.
    val trayAvailable = runCatching { SystemTray.isSupported() }.getOrDefault(false)
    val startVisible = args.contains("--panel") || !trayAvailable

    application {
        val state = remember { AppState().also { it.resolve(explicit) } }
        var visible by remember { mutableStateOf(startVisible) }

        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { state.refresh() }
                delay(POLL_SECONDS * 1000L)
            }
        }

        if (trayAvailable) {
            Tray(
                icon = BitmapPainter(
                    TrayIconRenderer
                        .render(
                            utilization = state.usage?.headline ?: 0.0,
                            size = 32,
                            mode = TrayIconRenderer.Mode.RING_WITH_PERCENT,
                            stale = state.stale,
                        )
                        .toComposeImageBitmap(),
                ),
                tooltip = state.usage?.let { "Battery — ${it.headline.toInt()}%" } ?: "Battery",
                onAction = { visible = !visible },
                menu = {
                    Item("Show", onClick = { visible = true })
                    Item("Refresh now", onClick = { state.refresh() })
                    Item("Quit", onClick = ::exitApplication)
                },
            )
        }

        Window(
            onCloseRequest = { if (trayAvailable) visible = false else exitApplication() },
            visible = visible,
            title = "Battery",
            state = rememberWindowState(
                size = DpSize(360.dp, state.panelHeight.dp),
                // Anchored bottom-right, above the notification area, which is
                // where a tray flyout belongs on Windows. Phase 4 replaces this
                // with the real tray-icon rectangle from Shell_NotifyIconGetRect.
                position = WindowPosition.Aligned(Alignment.BottomEnd),
            ),
            undecorated = trayAvailable,
            resizable = false,
            alwaysOnTop = trayAvailable,
        ) {
            BatteryTheme { Panel(state) }
        }
    }
}

/**
 * 60 seconds, matching macOS rather than Android's 180.
 *
 * The Android cadence is a battery decision — there, a poll is what keeps a
 * foreground service alive. A desktop is already awake, and the tray icon is a
 * number people glance at while working, so the tighter loop is free here in a
 * way it is not on a phone.
 */
private const val POLL_SECONDS = 60

internal fun Array<String>.valueOfFlag(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
