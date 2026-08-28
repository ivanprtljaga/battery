package com.allthingsclaude.battery.windows.dev

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.allthingsclaude.battery.windows.state.AppState
import com.allthingsclaude.battery.windows.tray.TrayIconRenderer
import com.allthingsclaude.battery.windows.ui.BatteryTheme
import com.allthingsclaude.battery.windows.ui.Panel
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
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
        // Draw into a scene taller than any panel and cut the empty part off
        // afterwards, rather than computing a height. A height computed from the
        // rows on screen is the same wrong answer the window used to hold: it
        // has to be right for every font at every scale, and Segoe UI is taller
        // than the Linux default by just enough to clip the last line.
        val scene = ImageComposeScene(
            width = (PANEL_WIDTH * density).toInt(),
            height = (SCENE_HEIGHT * density).toInt(),
            density = Density(density),
        ) {
            BatteryTheme(dark = dark) {
                Panel(state, Modifier.width(PANEL_WIDTH.dp).wrapContentHeight())
            }
        }
        try {
            scene.render().encodeToData()?.bytes?.let { bytes ->
                val trimmed = trimTransparentBottom(ImageIO.read(ByteArrayInputStream(bytes)))
                ImageIO.write(trimmed, "png", File(out, if (dark) "panel-dark.png" else "panel-light.png"))
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

/** The panel's width in dp, matching the window. */
private const val PANEL_WIDTH = 360

/** Taller than the panel can plausibly get; the excess is trimmed off. */
private const val SCENE_HEIGHT = 600

/**
 * Drop the fully transparent rows below the panel.
 *
 * The panel paints its own rounded surface onto an otherwise empty scene, so
 * "where the content ends" is a fact in the pixels rather than an estimate — the
 * same thing `pack()` does for the real window, done in the one place that has
 * no window to pack.
 */
private fun trimTransparentBottom(image: BufferedImage): BufferedImage {
    var bottom = image.height
    while (bottom > 1 && (0 until image.width).all { x -> (image.getRGB(x, bottom - 1) ushr 24) == 0 }) {
        bottom--
    }
    return if (bottom == image.height) image else image.getSubimage(0, 0, image.width, bottom)
}
