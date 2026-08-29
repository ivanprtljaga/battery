package com.allthingsclaude.battery.windows

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.allthingsclaude.battery.windows.dev.renderScreenshots
import com.allthingsclaude.battery.windows.notify.Alert
import com.allthingsclaude.battery.windows.notify.Toaster
import com.allthingsclaude.battery.windows.state.AppState
import com.allthingsclaude.battery.windows.tray.TrayAnchor
import com.allthingsclaude.battery.windows.tray.TrayIconRenderer
import com.allthingsclaude.battery.windows.tray.TrayMenuStyle
import com.allthingsclaude.battery.windows.ui.BatteryTheme
import com.allthingsclaude.battery.windows.ui.Panel
import com.allthingsclaude.battery.windows.win.StartWithWindows
import com.allthingsclaude.battery.windows.win.SystemTheme
import com.allthingsclaude.battery.windows.win.TaskbarButton
import com.allthingsclaude.battery.windows.win.WindowCorner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.roundToInt

/**
 * Phase 2: the tray icon and the flyout.
 *
 * ```
 * gradlew :app:run                       # tray icon; click it for the panel
 * gradlew :app:run --args="--panel"      # panel up front, for a screenshot
 * gradlew :app:run --args="--headless"   # Phase 1's console poller
 * gradlew :app:run --args="--screenshot build/shots"   # render every surface to PNG
 * gradlew :app:run --args="--toast"      # fire one sample toast and show it
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
    val startVisible = args.contains("--panel") || !trayAvailable || claimFirstRun()

    application {
        val state = remember {
            AppState(notify = { Toaster.show(it) }).also { it.resolve(explicit) }
        }
        var visible by remember { mutableStateOf(startVisible) }

        // Read once. Windows broadcasts a theme change and AWT does not forward
        // it, so following it live would mean a message-only window and a
        // subclass — a lot of Win32 for a setting people change twice a year.
        val dark = remember { SystemTheme.isDark() }

        // Whether Windows launches this at login, read from the registry rather
        // than remembered here: the user can turn it off in Task Manager's
        // Startup tab, and this has to agree with what they see there.
        var startsWithWindows by remember { mutableStateOf(StartWithWindows.enabled()) }

        // When focus loss last dismissed the panel.
        //
        // Clicking the tray icon while the panel is open does two things at
        // once: the shell takes focus, which dismisses it, and then the click
        // arrives and toggles it — straight back open. So a toggle that lands in
        // the moment after a dismissal is that same click, and is ignored.
        var dismissedAt by remember { mutableStateOf(0L) }

        // Whether the single-click listener below made it onto the AWT icon.
        // Until it does, the double click is the only way in, so `onAction` has
        // to keep working — and stop working the moment it would double up.
        var singleClickWorks by remember { mutableStateOf(false) }

        // Menu clicks poll, and polling is a credential read and a network call.
        // Running that on the event thread freezes the menu it was clicked from.
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { state.refresh() }
                // The state's own answer, not a constant: after a 429 this is
                // core's escalating backoff rather than another sixty seconds.
                delay(state.nextPollSeconds * 1000L)
            }
        }

        if (trayAvailable) {
            Tray(
                icon = BitmapPainter(
                    TrayIconRenderer
                        .render(
                            utilization = state.usage?.headline ?: 0.0,
                            // The size the shell actually rasterises to, in real
                            // pixels, so the bitmap is drawn rather than
                            // resampled. Handing over a smaller one and letting
                            // Windows stretch it is precisely the muddiness
                            // TrayIconRenderer draws per-size to avoid.
                            size = trayIconSize(),
                            mode = TrayIconRenderer.Mode.RING_WITH_PERCENT,
                            stale = state.stale,
                        )
                        .toComposeImageBitmap(),
                ),
                tooltip = state.usage?.let { "Battery — ${it.headline.toInt()}%" } ?: "Battery",
                onAction = { if (!singleClickWorks) visible = !visible },
                menu = {
                    Item("Show", onClick = { visible = true })
                    Item("Refresh now", onClick = { scope.launch(Dispatchers.IO) { state.refresh() } })
                    Separator()
                    // Which install to read. Two are normal on a machine with
                    // WSL, both can be signed in, and their session windows are
                    // different numbers — so this is a preference, and the app
                    // has no business guessing it twice.
                    Menu("Source") {
                        if (state.sources.isEmpty()) {
                            Item("No Claude Code directory", enabled = false, onClick = {})
                        }
                        // CheckboxItem rather than RadioButtonItem, which is the
                        // shape this actually is: AWT's menus are built from
                        // java.awt.CheckboxMenuItem and have no radio at all, so
                        // Compose throws rather than approximating one.
                        state.sources.forEach { source ->
                            CheckboxItem(
                                // The account, not just the location: two config
                                // directories in one distro both read
                                // "WSL: Ubuntu", and the thing being chosen
                                // between is whose usage to show.
                                // A hyphen, not an em dash. AWT's menus draw
                                // with a font that has no glyph for U+2014 and
                                // renders it as a box — seen in the menu, not
                                // guessed at.
                                text = state.accountFor(source)
                                    ?.let { "${source.label} - $it" }
                                    ?: source.label,
                                checked = state.dir?.path == source.path,
                                onCheckedChange = { wanted ->
                                    // Unticking the active source would leave the
                                    // app with none; the only gesture that means
                                    // anything here is ticking a different one.
                                    if (wanted) scope.launch(Dispatchers.IO) { state.select(source) }
                                },
                            )
                        }
                    }
                    CheckboxItem(
                        text = "Notify at 80/90/95%",
                        checked = state.notifications,
                        onCheckedChange = { state.allowNotifications(it) },
                    )
                    // Absent rather than disabled when there is no exe to
                    // register. Under Gradle the launcher is a JDK that could
                    // not start this app on its own, and a switch that writes a
                    // Run entry failing silently at every login is worse than no
                    // switch.
                    if (StartWithWindows.launchTarget() != null) {
                        CheckboxItem(
                            text = "Start with Windows",
                            checked = startsWithWindows,
                            onCheckedChange = { wanted ->
                                if (StartWithWindows.set(wanted)) startsWithWindows = wanted
                            },
                        )
                    }
                    Separator()
                    Item("Quit", onClick = ::exitApplication)

                    // After the items, and on every recomposition. Compose
                    // rebuilds them as the source list and the tick states
                    // change, and an item created after a one-off restyle would
                    // come back at the peer's default size.
                    SideEffect { TrayMenuStyle.apply() }
                },
            )

            // Open on a *single* left click.
            //
            // Compose's `onAction` is AWT's `TrayIcon` ActionListener, and on
            // Windows that fires on a double click. Every native flyout in the
            // notification area — the clock, the network, the volume — opens on
            // one click, so as shipped the icon looked dead to anyone who
            // clicked it the way Windows taught them. Confirmed by clicking it:
            // once did nothing, twice opened the panel.
            //
            // Compose exposes no hook for this, so the listener goes on the AWT
            // object directly. `SystemTray.trayIcons` is the way back to the
            // icon Compose registered, and this effect runs after the one that
            // registered it.
            // Whether a toast actually reaches the notification centre is not
            // something a test can answer — the same gap `--screenshot` fills
            // for the panel. One sample alert, on demand, through the exact path
            // a real threshold crossing takes.
            if (args.contains("--toast")) {
                LaunchedEffect(Unit) {
                    delay(1500)
                    Toaster.show(
                        Alert(
                            "Session usage at 90%",
                            "Your five-hour Claude Code window is at 91%.",
                        ),
                    )
                }
            }

            DisposableEffect(Unit) {
                val listener = object : MouseAdapter() {
                    override fun mouseReleased(event: MouseEvent) {
                        if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
                        if (System.currentTimeMillis() - dismissedAt < DISMISS_GRACE_MILLIS) return
                        visible = !visible
                    }
                }
                val icons = runCatching { SystemTray.getSystemTray().trayIcons }
                    .getOrDefault(emptyArray())
                icons.forEach { it.addMouseListener(listener) }
                singleClickWorks = icons.isNotEmpty()
                onDispose {
                    icons.forEach { it.removeMouseListener(listener) }
                    singleClickWorks = false
                }
            }
        }

        Window(
            onCloseRequest = { if (trayAvailable) visible = false else exitApplication() },
            visible = visible,
            title = "Battery",
            state = rememberWindowState(
                // Unspecified so the window packs around its content.
                // A fixed height was measured against one machine's fonts and
                // clipped the reset labels on Windows, where Segoe UI is taller
                // than the Linux default — a number that has to be right on
                // every font at every DPI is a number that will be wrong.
                size = DpSize.Unspecified,
                // A first guess only, so the window does not appear in the
                // middle of the screen before it is placed. TrayAnchor moves it
                // onto the real icon below, and this is what stands when there
                // is no icon to anchor to.
                position = WindowPosition.Aligned(Alignment.BottomEnd),
            ),
            undecorated = trayAvailable,
            resizable = false,
            alwaysOnTop = trayAvailable,
        ) {
            BatteryTheme(dark = dark) {
                // Square, and filling the window edge to edge. Rounding the
                // panel inside an opaque square window is what left white
                // wedges in the corners: a clip paints a shape, it does not
                // remove pixels from a window. WindowCorner asks the compositor
                // to take the corners off instead.
                Panel(state, Modifier.width(360.dp).wrapContentHeight(), corner = 0.dp)
            }

            // Compose sizes a `DpSize.Unspecified` window to its content exactly
            // once, while the window is still undisplayable — so the first
            // layout wins, and the first layout is the empty state, one line of
            // "waiting for the first reading". When the reading arrives the
            // panel grows three gauges taller and the window does not, which is
            // the clipping this was meant to cure rather than a fixed height.
            //
            // So re-pack by hand whenever the panel changes shape. Clearing the
            // preferred size first is the point: `pack()` alone would re-apply
            // the frozen one.
            //
            // Then put the window on the icon. Deliberately after the pack, in
            // that order — the flyout is anchored by its *bottom* edge, which is
            // not known until the height is.
            // Panel-gated, which on Windows is not a nicety. Reading the
            // transcripts means megabytes across 9P into a virtual machine, and
            // nobody needs a seven-day chart recomputed behind a closed window.
            LaunchedEffect(visible, state.dir, state.details) {
                if (visible) withContext(Dispatchers.IO) { state.loadHistory() }
            }

            LaunchedEffect(state.usage == null, state.message, state.stats, state.details, visible) {
                if (!visible) return@LaunchedEffect
                withFrameNanos { }
                window.preferredSize = null
                window.pack()
                TrayAnchor.flyoutOrigin(window.size)?.let(window::setLocation)
                // A flyout opened from the tray arrives without focus, and
                // Windows spends the first click inside an unfocused window on
                // activating it. That is correct for an app window and wrong
                // here: the panel has one control, and clicking it once did
                // nothing. Found by clicking it.
                window.toFront()
                window.requestFocus()
            }

            // Dismiss when it loses focus, which is what a flyout is.
            //
            // Every native one does it — the clock, the volume, the network — and
            // without it this panel stays on top of whatever the user clicked
            // next, which is the one behaviour an always-on-top window must not
            // have. Only meaningful with a tray icon to reopen it from; the WSLg
            // fallback window is the app's only surface and closing it on focus
            // loss would leave nothing.
            DisposableEffect(trayAvailable) {
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowLostFocus(event: java.awt.event.WindowEvent) {
                        if (!trayAvailable) return
                        dismissedAt = System.currentTimeMillis()
                        visible = false
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }

            // Windows 11 rounds a framed window on its own and leaves an
            // undecorated one square, so this has to be asked for. Once is
            // enough — it is a property of the window, not of the frame.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                WindowCorner.round(window)
                // A flyout is not an app window. Left alone it takes a taskbar
                // button that appears and disappears as the panel opens, wearing
                // the Java icon, next to a tray icon that is already this app's
                // presence on the taskbar.
                if (trayAvailable) TaskbarButton.hide(window)
            }
        }
    }
}

/**
 * How long after a focus-loss dismissal a tray click is treated as the cause of
 * it rather than as a fresh request to open.
 *
 * Long enough to cover the gap between the shell taking focus and the mouse
 * release reaching AWT, short enough that a deliberate second click still
 * reopens the panel.
 */
private const val DISMISS_GRACE_MILLIS = 250L

/**
 * The notification area's icon size, in **physical pixels**.
 *
 * `SystemTray.trayIconSize` does not answer that. Measured on a 150% display it
 * returns 16, the same as at 100%: the number is in AWT's scaled user space, not
 * in pixels, while the shell rasterises the icon at 24. Rendering 16 and letting
 * Windows stretch it to 24 is exactly the soft, smeared icon this app renders
 * per size to avoid — and it also kept every scaled display below
 * [TrayIconRenderer.PERCENT_THRESHOLD], so the ring silently lost its number on
 * exactly the machines with room for it.
 *
 * Multiplying by the screen's scale factor is what recovers the real figure.
 * Clamped because a hostile or absent answer should degrade to a small clean
 * icon rather than to something enormous.
 */
private fun trayIconSize(): Int = runCatching {
    val logical = SystemTray.getSystemTray().trayIconSize.width
    val scale = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    (logical * scale).roundToInt()
}.getOrDefault(16).coerceIn(16, 64)

/**
 * Whether this is the first launch, marking it seen as a side effect.
 *
 * **Windows hides a new notification-area icon behind the overflow chevron.**
 * There is no supported way to promote your own icon out of it — only the user
 * dragging it onto the taskbar — so a first run that starts minimised to the
 * tray is, from the user's side, an app that did nothing at all. Observed on a
 * real machine, where the run looked like a hang.
 *
 * macOS has no equivalent problem: a menu bar item is simply there. So this is
 * one of the few places the Windows app must behave differently from its
 * sibling rather than the same.
 *
 * A failure to read or write the marker returns false — showing the panel is the
 * recoverable mistake, and an unwritable home directory should not mean a window
 * on every single launch.
 */
private fun claimFirstRun(): Boolean = try {
    val marker = java.io.File(System.getProperty("user.home"), ".battery/windows-seen")
    if (marker.exists()) {
        false
    } else {
        marker.parentFile?.mkdirs()
        marker.writeText(java.time.Instant.now().toString())
        true
    }
} catch (_: Exception) {
    false
}

internal fun Array<String>.valueOfFlag(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
