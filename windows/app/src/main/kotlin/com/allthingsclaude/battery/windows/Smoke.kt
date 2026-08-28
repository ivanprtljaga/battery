package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.AppConfig
import com.allthingsclaude.battery.core.BatteryPalette
import com.allthingsclaude.battery.core.UsageLevel

/**
 * Phase 0's only deliverable: proof that this build compiles and links against
 * `../android/core` without moving it and without an Android SDK.
 *
 * It asserts nothing and polls nothing. If it prints, the borrowed-module wiring
 * in `settings.gradle.kts` works, which is the single question Phase 0 exists to
 * answer. Phase 1 replaces it with the headless poller.
 */
fun main() {
    println("Battery for Windows — core wiring smoke test")
    println("Endpoint:  ${AppConfig.API_BASE_URL}${AppConfig.USAGE_PATH}")
    println("OAuth:     ${AppConfig.OAUTH_AUTHORIZE_URL}")
    println("Poll:      ${AppConfig.ACTIVE_POLL_SECONDS}s active / ${AppConfig.IDLE_POLL_SECONDS}s idle")
    println()
    println("Level ramp (shared with macOS, iOS and Android):")
    listOf(10.0, 55.0, 80.0, 95.0).forEach { utilization ->
        val level = UsageLevel.from(utilization)
        println(
            "  %5.1f%%  %-8s  #%06X  alarming=%s".format(
                utilization,
                level.label,
                level.color and 0xFFFFFF,
                level.isAlarming,
            )
        )
    }
    println()
    println("Brand: #%06X".format(BatteryPalette.BRAND and 0xFFFFFF))
}
