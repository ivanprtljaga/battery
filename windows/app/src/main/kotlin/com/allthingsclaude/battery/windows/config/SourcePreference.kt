package com.allthingsclaude.battery.windows.config

import java.io.File

/**
 * Which Claude Code directory the user pinned, remembered across launches.
 *
 * A Windows machine with WSL routinely has two working installs — the same
 * account signed in on both, each with a live credential — and nothing about
 * either makes it the right one. [com.allthingsclaude.battery.windows.auth.DirSelection]
 * settles the case where only one *can* answer; this settles the case where both
 * can, which it cannot, because the answer is a preference rather than a fact.
 * The two 5-hour session windows are genuinely different numbers, so guessing is
 * visibly wrong rather than merely arbitrary.
 *
 * **The origin and distro are stored, not just the path.** That is the whole
 * point of the file format. A bare `\\wsl.localhost\...` path replayed as an
 * [DirOrigin.EXPLICIT] directory would skip the running-distro gate in
 * `CredentialBridge` and read the path directly — which starts the distro, the
 * one behaviour this app is built to avoid. Restoring the origin keeps the gate,
 * so a pinned distro that is shut down reports "Ubuntu isn't running" instead of
 * booting a VM to answer a question nobody asked.
 *
 * A pinned directory is also honoured when it is *absent* from the current
 * candidates, and that is deliberate too: falling back to the other install
 * would silently answer with a different account's session window under a label
 * the user did not choose.
 */
object SourcePreference {

    /** Beside `windows-seen`, which is already this app's one bit of local state. */
    fun defaultFile(): File = File(System.getProperty("user.home"), ".battery/windows-source")

    /**
     * Tab-separated `origin`, `distro`, `path` — one line, no escaping.
     *
     * A tab cannot occur in a Windows path, so the format needs no quoting and
     * stays greppable. `Properties` would work and would escape every backslash
     * in a UNC path, which is a file nobody can read.
     */
    private const val SEPARATOR = "\t"

    /** The pinned directory, or null when there is none or it cannot be read. */
    fun load(file: File = defaultFile()): ClaudeDir? = runCatching {
        val parts = file.readText().trim().split(SEPARATOR)
        if (parts.size != 3) return null
        val origin = DirOrigin.entries.firstOrNull { it.name == parts[0] } ?: return null
        val path = parts[2].takeIf { it.isNotBlank() } ?: return null
        ClaudeDir(path, origin, parts[1].takeIf { it.isNotBlank() })
    }.getOrNull()

    /** Pin [dir]. A failure to write costs the preference, never the poll. */
    fun save(dir: ClaudeDir, file: File = defaultFile()) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(listOf(dir.origin.name, dir.distro.orEmpty(), dir.path).joinToString(SEPARATOR))
        }
    }

    /** Forget the pin and go back to choosing automatically. */
    fun clear(file: File = defaultFile()) {
        runCatching { file.delete() }
    }
}
