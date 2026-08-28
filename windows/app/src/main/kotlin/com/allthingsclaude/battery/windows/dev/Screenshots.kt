package com.allthingsclaude.battery.windows.dev

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.allthingsclaude.battery.windows.state.AppState
import com.allthingsclaude.battery.windows.tray.TrayIconRenderer
import com.allthingsclaude.battery.windows.ui.BatteryTheme
import com.allthingsclaude.battery.windows.ui.Panel
import java.io.File
import javax.imageio.ImageIO

/**
 * Render every surface to PNG without opening a window.
 *
 * This exists because the thing being built is a Windows tray app and it is
 * being built on Linux. `ImageComposeScene` draws the panel through the same
 * Skia path a real window uses, so the output is the actual rendering rather
 * than an approximation — which makes it the only way to review a layout change
 * here, and a reasonable way to review one anywhere without launching anything.
 *
 * ```
 * gradlew :app:run --args="--screenshot build/shots"
 * gradlew :app:run --args="--screenshot build/shots --dir /home/me/.claude"
 * ```
 */
fun renderScreenshots(outputDir: String, state: AppState, density: Float = 2f) {
    val out = File(outputDir).apply { mkdirs() }

    for (dark in listOf(true, false)) {
        val scene = ImageComposeScene(
            width = (360 * density).toInt(),
            height = (state.panelHeight * density).toInt(),
            density = Density(density),
        ) {
            BatteryTheme(dark = dark) { Panel(state) }
        }
        try {
            scene.render().encodeToData()?.bytes?.let { bytes ->
                File(out, if (dark) "panel-dark.png" else "panel-light.png").writeBytes(bytes)
            }
        } finally {
            scene.close()
        }
    }

    // The tray icons, every size Windows asks for against every mode, laid out
    // as one strip so the sizes can be compared at a glance — 16 px is where
    // legibility actually fails, and it fails differently per mode.
    val utilization = state.usage?.headline ?: 63.0
    for (mode in TrayIconRenderer.Mode.entries) {
        for (size in TrayIconRenderer.SIZES) {
            val image = TrayIconRenderer.render(utilization, size, mode)
            ImageIO.write(image, "png", File(out, "tray-${mode.name.lowercase()}-$size.png"))
        }
    }

    println("Wrote ${out.listFiles()?.size ?: 0} images to ${out.absolutePath}")
}
