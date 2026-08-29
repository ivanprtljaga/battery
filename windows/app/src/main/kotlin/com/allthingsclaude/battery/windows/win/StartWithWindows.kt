package com.allthingsclaude.battery.windows.win

import java.io.File

/**
 * Starting with Windows, the way a per-user app is supposed to.
 *
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, not a Startup-folder
 * shortcut and not a scheduled task. The Run key is per-user like the installer,
 * needs no administrator, and is the one place Task Manager's Startup tab reads
 * — so a user who wants this off can turn it off where they expect to, and this
 * app will see that and agree, because [enabled] asks the registry rather than
 * remembering its own answer.
 *
 * A scheduled task would also work and is what an installer might do; it needs
 * elevation to create, which this app has deliberately never asked for.
 *
 * `reg.exe` rather than JNA. Advapi32's registry calls are a dozen lines of
 * handles and buffers for something the shell ships a tool for; the tool's exit
 * code is the whole error story, and none of this is on a hot path.
 */
object StartWithWindows {

    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Battery"

    /**
     * The executable to launch, or null when there is not a sensible one.
     *
     * Null under Gradle, deliberately. `java.home` there is a JDK that would
     * need the whole classpath to start anything, so registering it would write
     * a Run entry that fails silently at every login. The packaged app is a real
     * `Battery.exe` next to its own runtime, and that is the only shape worth
     * offering to launch.
     */
    fun launchTarget(): String? {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        return command.takeIf { it.endsWith("Battery.exe", ignoreCase = true) && File(it).isFile }
    }

    /** Whether Windows will start this app at login. */
    fun enabled(): Boolean = runCatching {
        val output = ProcessBuilder("reg", "query", KEY, "/v", NAME)
            .redirectErrorStream(true)
            .start()
            .let { it.inputStream.bufferedReader().readText().also { _ -> it.waitFor() } }
        output.contains(NAME)
    }.getOrDefault(false)

    /**
     * Turn it on or off. Returns whether the registry now says what was asked.
     *
     * Quoted with `\"`: the path contains spaces on any normal install
     * (`C:\Users\First Last\AppData\...`), and an unquoted Run value is parsed
     * up to the first one — which is a login that launches `C:\Users\First.exe`
     * and reports nothing.
     */
    fun set(enabled: Boolean): Boolean = runCatching {
        val command = if (enabled) {
            val target = launchTarget() ?: return false
            listOf("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"$target\"", "/f")
        } else {
            listOf("reg", "delete", KEY, "/v", NAME, "/f")
        }
        ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)
}
