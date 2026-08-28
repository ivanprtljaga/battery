package com.allthingsclaude.battery.windows.config

import java.io.File

/**
 * Whether to interrupt the user at all.
 *
 * macOS offers three switches, one per threshold. One switch here, because the
 * question people actually have is "stop talking to me" rather than "talk to me
 * at ninety but not at eighty" — and because the usual Windows answer, turning
 * the app off in Settings › Notifications, does not work for an unpackaged app
 * whose AppUserModelID resolves to no Start Menu entry. Until Phase 5 registers
 * one, this switch is the only one there is.
 *
 * Deliberately a second small file rather than a shared key-value store with
 * [SourcePreference]. Two of these is duplication; one abstraction over two
 * callers is the kind of indirection that has to be read before either can be.
 */
object NotifyPreference {

    private fun defaultFile(): File =
        File(System.getProperty("user.home"), ".battery/windows-notify")

    /** On unless the user has said otherwise, including when the file is unreadable. */
    fun enabled(file: File = defaultFile()): Boolean =
        runCatching { file.readText().trim() != "off" }.getOrDefault(true)

    fun set(enabled: Boolean, file: File = defaultFile()) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(if (enabled) "on" else "off")
        }
    }
}
