package com.allthingsclaude.battery.windows.config

import java.io.File

/**
 * The app's on/off settings, remembered across launches.
 *
 * `NotifyPreference` carried a note saying two of these would be duplication and
 * one abstraction over two callers would be worse. This is the third, which is
 * where that stops being true — so the file reading moves here once and the
 * settings become one line each.
 *
 * Still one small file per flag under `~/.battery/`, not a properties blob:
 * a corrupt or unreadable file costs exactly the setting it holds, the names are
 * greppable, and deleting one by hand is a supported way to reset it.
 *
 * [SourcePreference] deliberately stays separate. It stores a record — an
 * origin, a distro and a path that have to agree with each other — not a switch.
 */
object Flags {

    /**
     * Whether threshold toasts are allowed.
     *
     * macOS offers three switches, one per threshold. One here, because the
     * question people actually have is "stop talking to me" — and because the
     * usual Windows answer, Settings › Notifications, does not list an
     * unpackaged app whose AppUserModelID resolves to no Start Menu entry.
     */
    val notifications = Flag("windows-notify", default = true)

    /**
     * Whether the panel shows the seven-day chart and the project breakdown.
     *
     * The port of macOS's `showDetails` switch, which gates the same thing there:
     * `ProjectTokenBreakdownView` under each gauge. It earns more here than it
     * does on a Mac, because turning it off also stops the read — the
     * transcripts are megabytes across a 9P redirector into a virtual machine,
     * and nobody should pay that for a section they have collapsed.
     */
    val details = Flag("windows-details", default = true)

    class Flag(private val fileName: String, private val default: Boolean) {

        private fun file(): File = File(System.getProperty("user.home"), ".battery/$fileName")

        /** The stored value, or [default] when the file is missing or unreadable. */
        fun get(): Boolean = runCatching {
            when (file().readText().trim()) {
                "on" -> true
                "off" -> false
                else -> default
            }
        }.getOrDefault(default)

        /** A failure to write costs the setting, never the poll. */
        fun set(value: Boolean) {
            runCatching {
                val target = file()
                target.parentFile?.mkdirs()
                target.writeText(if (value) "on" else "off")
            }
        }
    }
}
