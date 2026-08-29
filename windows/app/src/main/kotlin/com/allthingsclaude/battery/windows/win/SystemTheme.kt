package com.allthingsclaude.battery.windows.win

/**
 * Whether Windows is in dark mode.
 *
 * The panel was dark unconditionally, which is not a design decision so much as
 * a decision nobody made — `BatteryTheme` has rendered both since Phase 2 and
 * `--screenshot` proves it every run. On a light desktop an always-dark flyout
 * reads as a bug rather than as a preference.
 *
 * `AppsUseLightTheme` under `HKCU\...\Themes\Personalize` is the setting behind
 * Settings › Personalisation › Colours › "Choose your default app mode". There
 * is a `SystemUsesLightTheme` beside it for the taskbar and Start; apps read the
 * first one, which is why a taskbar can be dark while a window is light.
 *
 * `reg.exe` for the same reason as [StartWithWindows]: this is one value, read
 * once at startup, and Advapi32 is a dozen lines of handles for it.
 *
 * Absent or unreadable means dark. Windows 11 defaults to dark app mode and the
 * app's own brand surface is dark, so that is the safer way to be wrong.
 */
object SystemTheme {

    private const val KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    fun isDark(): Boolean = runCatching {
        val process = ProcessBuilder("reg", "query", KEY, "/v", "AppsUseLightTheme")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        // `AppsUseLightTheme    REG_DWORD    0x0` — zero is dark, and the value
        // is the *light* flag, so the name reads backwards from what it decides.
        val value = Regex("REG_DWORD\\s+0x([0-9a-fA-F]+)").find(output)?.groupValues?.get(1)
        value?.toIntOrNull(16) == 0
    }.getOrDefault(true)
}
