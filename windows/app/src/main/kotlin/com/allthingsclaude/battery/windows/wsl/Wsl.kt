package com.allthingsclaude.battery.windows.wsl

import java.nio.charset.StandardCharsets

/**
 * Talking to `wsl.exe`.
 *
 * The one rule this file exists to enforce: **listing distros must never start
 * one.** Touching a `\\wsl.localhost` path boots the distro if it is not already
 * running, so a tray app that read a credential every minute would silently hold
 * a Linux VM — and a gigabyte of RAM — open all day on a machine whose owner
 * never opened a terminal. `wsl.exe -l --running -q` is the only question that
 * can be asked for free, and every path access waits on its answer.
 */
interface WslCommand {
    /**
     * A `wsl.exe` control command, whose output is **wsl.exe's own** — UTF-16LE.
     *
     * @return stdout, already decoded, or null if wsl.exe could not be run.
     */
    fun run(vararg args: String): String?

    /**
     * Run a program *inside* [distro] and return its stdout.
     *
     * Split from [run] because the encoding differs and getting it wrong is
     * silent: `wsl.exe` writes its own listings as UTF-16LE, but when it is
     * merely a launcher the bytes belong to the Linux process, which writes
     * UTF-8. Decoding `whoami` as UTF-16LE yields a name matching no user.
     *
     * **This starts [distro] if it is not running** — only ever call it for a
     * distro `running()` has already named.
     */
    fun exec(distro: String, vararg command: String): String? = null
}

/**
 * The real thing.
 *
 * **`wsl.exe` writes UTF-16LE, not the platform charset.** Verified by hexdump
 * against a live install (`55 00 62 00 75 00 …` for "Ubuntu"), not assumed.
 * Reading it with the JVM default yields a NUL-interleaved string that compares
 * equal to no distro name anyone will ever ask for, and the failure is silent —
 * every lookup simply misses. This is the most likely thing here to break if
 * Microsoft ever changes it, so the decode is named rather than implicit.
 */
object RealWslCommand : WslCommand {
    override fun run(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("wsl.exe") + args)
            .redirectErrorStream(false)
            .start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        if (process.exitValue() == 0) String(bytes, StandardCharsets.UTF_16LE) else null
    } catch (_: Exception) {
        // No wsl.exe at all is an ordinary state — a Windows box without WSL
        // installed, or this code running somewhere that is not Windows. The
        // caller reads null as "no distros", never as an error.
        null
    }

    override fun exec(distro: String, vararg command: String): String? = try {
        val process = ProcessBuilder(listOf("wsl.exe", "-d", distro, "--") + command)
            .redirectErrorStream(false)
            .start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        // UTF-8, not UTF-16LE: these bytes came from a Linux program, not from
        // wsl.exe. See the interface doc.
        if (process.exitValue() == 0) String(bytes, StandardCharsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }
}

object Wsl {

    /**
     * Distros that are running *right now*.
     *
     * Free of side effects, which is the entire point: this is the gate every
     * WSL path access passes through.
     */
    fun running(command: WslCommand = RealWslCommand): List<String> =
        parseDistroList(command.run("-l", "--running", "-q"))

    /** Every installed distro, running or not. For the folder picker only. */
    fun installed(command: WslCommand = RealWslCommand): List<String> =
        parseDistroList(command.run("-l", "-q"))

    /**
     * Split from the process call so the format is pinned by a test rather than
     * trusted — the output belongs to another program and is undocumented.
     *
     * Handles CRLF, the trailing blank line, and a stray BOM, any of which would
     * otherwise yield a distro named `"Ubuntu\r"` that matches nothing.
     */
    fun parseDistroList(raw: String?): List<String> =
        raw.orEmpty()
            .removePrefix("\uFEFF") // escaped: an invisible BOM in source is unreviewable
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * A distro's filesystem as Windows sees it.
     *
     * `\\wsl.localhost\` rather than the older `\\wsl$\`: both resolve on
     * current Windows, but `wsl$` is the legacy spelling and the newer one is
     * what Explorer and `wsl.exe` themselves produce.
     */
    fun uncRoot(distro: String): String = "\\\\wsl.localhost\\$distro"

    /** A POSIX path inside [distro], spelled the way Win32 needs it. */
    fun uncPath(distro: String, posixPath: String): String =
        uncRoot(distro) + posixPath.replace('/', '\\')
}
