package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.core.UsageBucket
import com.allthingsclaude.battery.core.UsageLevel
import com.allthingsclaude.battery.windows.auth.CredentialBridge
import com.allthingsclaude.battery.windows.auth.CredentialLookup
import com.allthingsclaude.battery.windows.auth.TokenCache
import com.allthingsclaude.battery.windows.config.ClaudeConfigDir
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import com.allthingsclaude.battery.windows.poll.PollResult
import com.allthingsclaude.battery.windows.poll.UsagePoller
import com.allthingsclaude.battery.windows.wsl.Wsl
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Phase 1: the headless poller.
 *
 * No window, no tray icon. Every genuinely risky thing about this port — finding
 * Claude Code's directory when it is inside WSL, reading a credential without
 * booting a distro, failing closed when it cannot — is answerable at a console,
 * and answering it here means Phase 2 can be about drawing rather than about
 * discovering that the token was NUL-interleaved all along.
 *
 * ```
 * gradlew :app:run                        # resolve, report, poll once
 * gradlew :app:run --args="--watch"       # keep polling
 * gradlew :app:run --args="--dir <path>"  # an explicit directory
 * ```
 */
fun main(args: Array<String>) {
    val explicit = args.valueOf("--dir") ?: System.getenv("BATTERY_CLAUDE_DIR")
    val watch = args.contains("--watch")

    println("Battery for Windows — Phase 1 (headless)")
    println()

    val dirs = if (explicit != null) {
        // An explicit directory is taken at its word and never distro-gated: the
        // caller has named a path they can already reach. This is the headless
        // stand-in for the macOS folder picker, and the only way to exercise
        // this code on a machine that is not Windows.
        listOf(ClaudeDir(ClaudeConfigDir.normalize(explicit), DirOrigin.WINDOWS_NATIVE))
    } else {
        ClaudeConfigDir.candidates()
    }

    reportEnvironment(explicit)

    if (dirs.isEmpty()) {
        println("No Claude Code directory found.")
        println("  Pass --dir <path>, or set BATTERY_CLAUDE_DIR.")
        return
    }

    dirs.forEachIndexed { index, dir ->
        if (index > 0) println()
        report(dir)
    }

    val target = dirs.first()
    val poller = UsagePoller(cache = TokenCache())

    do {
        println()
        println("[${Instant.now()}] polling ${target.label}")
        when (val result = poller.poll(target)) {
            is PollResult.Ok -> printUsage(result)
            is PollResult.Blocked -> println("  blocked: ${result.lookup.message}")
            is PollResult.Failed -> println("  failed:  ${result.error.message}")
        }
        if (watch) Thread.sleep(Duration.ofSeconds(60).toMillis())
    } while (watch)
}

private fun reportEnvironment(explicit: String?) {
    val running = Wsl.running()
    println("WSL distros running: ${if (running.isEmpty()) "(none, or no wsl.exe)" else running.joinToString()}")
    if (explicit != null) println("Directory override:  $explicit")
    println()
}

private fun report(dir: ClaudeDir) {
    println("${dir.label}: ${dir.path}")
    println("  valid:    ${ClaudeConfigDir.looksValid(dir.path)}")

    val configPath = ClaudeConfigDir.configFile(dir.path)
    val identity = runCatching { File(ClaudeConfigDir.onThisPlatform(configPath)).readText() }
        .getOrNull()
        ?.let { ClaudeConfigDir.identity(it) }
    if (identity == null) {
        println("  identity: unknown ($configPath)")
    } else {
        println("  identity: ${identity.email ?: identity.accountUuid}")
        identity.organizationName?.let { println("  org:      $it") }
    }

    when (val lookup = CredentialBridge.read(dir)) {
        is CredentialLookup.Available -> {
            val seconds = lookup.tokens.expiryInstant.epochSecond - Instant.now().epochSecond
            println("  token:    valid for ${seconds / 3600}h ${(seconds % 3600) / 60}m")
        }
        is CredentialLookup.Unavailable ->
            println("  token:    ${lookup.message} [${lookup.reason}]")
    }
}

private fun printUsage(result: PollResult.Ok) {
    val usage = result.usage
    usage.fiveHour?.let { line("  session", it) }
    line("  weekly ", usage.sevenDay)
    usage.sevenDayOpus?.let { line("  opus   ", it) }
    usage.scopedWeekly?.let {
        println("  %-8s %5.1f%%  %s".format(it.label, it.utilization, resets(it.resetsAt)))
    }
    usage.extraUsage?.takeIf { it.isPresentable }?.let {
        println("  extra    ${it.format(it.used)} of ${it.format(it.limit)}")
    }
}

private fun line(label: String, bucket: UsageBucket) {
    val level = UsageLevel.from(bucket.utilization)
    println("%s %5.1f%%  %-8s %s".format(label, bucket.utilization, level.label, resets(bucket.resetsAt)))
}

private fun resets(at: Instant?): String {
    if (at == null) return ""
    val seconds = at.epochSecond - Instant.now().epochSecond
    if (seconds <= 0) return "(resetting)"
    return "resets in ${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun Array<String>.valueOf(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
