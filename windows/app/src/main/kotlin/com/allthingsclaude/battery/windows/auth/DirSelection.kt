package com.allthingsclaude.battery.windows.auth

import com.allthingsclaude.battery.windows.config.ClaudeDir

/**
 * Which of several Claude Code directories to actually poll.
 *
 * A Windows machine routinely has more than one. The observed case, and the one
 * that motivated this file: a native install at `%USERPROFILE%\.claude` that was
 * signed in once and no longer holds a credential, sitting alongside a live WSL
 * install that does. Both are real directories, both carry the same identity in
 * their `.claude.json`, and only one of them can answer a poll.
 *
 * [ClaudeConfigDir.candidates] deliberately orders by cost — the native path
 * first, because it needs no distro probe. That is the right order to *offer*
 * directories in and the wrong one to *choose* from, which is the distinction
 * this object exists to draw. Picking the first candidate blocked the app on an
 * empty directory while a working credential sat one line below it.
 */
object DirSelection {

    /**
     * The first directory whose credential can be read, or the first of any kind
     * when none can.
     *
     * Falling back rather than returning null is deliberate: a directory that
     * cannot answer still has a name, an identity and a *reason*, and showing
     * "Ubuntu isn't running" against a real directory tells the user more than
     * an empty panel does.
     *
     * Reading each candidate costs one file read, and only for directories
     * already cleared by the running-distro gate — so this never starts a
     * distro, and the [read] seam keeps that testable.
     */
    fun pick(
        candidates: List<ClaudeDir>,
        read: (ClaudeDir) -> CredentialLookup = { CredentialBridge.read(it) },
    ): ClaudeDir? = candidates.firstOrNull { read(it) is CredentialLookup.Available }
        ?: candidates.firstOrNull()
}
