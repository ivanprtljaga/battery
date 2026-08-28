package com.allthingsclaude.battery.windows.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.allthingsclaude.battery.core.BatteryPalette

/**
 * The terracotta, unchanged.
 *
 * Every value here comes from `core`'s [BatteryPalette], which is itself a
 * verbatim copy of `ios/BatteryKit/BatteryColors.swift`. Nothing about running
 * on Windows justifies a fourth opinion about what colour the brand is, so this
 * file converts and does not decide.
 */
data class BatteryColors(
    val brand: Color,
    val brandDark: Color,
    val brandDeep: Color,
    val secondary: Color,
    val surface: Color,
    val onSurface: Color,
) {
    companion object {
        val Light = BatteryColors(
            brand = Color(BatteryPalette.BRAND),
            brandDark = Color(BatteryPalette.BRAND_DARK),
            brandDeep = Color(BatteryPalette.BRAND_DEEP),
            secondary = Color(BatteryPalette.SECONDARY),
            surface = Color(BatteryPalette.SURFACE_LIGHT),
            onSurface = Color(0xFF1A1A1A),
        )

        val Dark = BatteryColors(
            brand = Color(BatteryPalette.BRAND),
            brandDark = Color(BatteryPalette.BRAND_DARK),
            brandDeep = Color(BatteryPalette.BRAND_DEEP),
            secondary = Color(BatteryPalette.SECONDARY),
            surface = Color(BatteryPalette.SURFACE_DARK),
            onSurface = Color(0xFFF2EFE9),
        )
    }
}

val LocalBatteryPalette = staticCompositionLocalOf { BatteryColors.Dark }

@Composable
fun BatteryTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalBatteryPalette provides if (dark) BatteryColors.Dark else BatteryColors.Light,
        content = content,
    )
}
