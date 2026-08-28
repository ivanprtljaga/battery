package com.allthingsclaude.battery.windows.auth

import com.allthingsclaude.battery.core.StoredTokens
import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.ClaudeConfigDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import com.allthingsclaude.battery.windows.wsl.RealWslCommand
import com.allthingsclaude.battery.windows.wsl.Wsl
import com.allthingsclaude.battery.windows.wsl.WslCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * The bridge to Claude Code's own credential — the Windows port of
 * `Sources/Services/LiveCredentials.swift`, and simpler than the original.
 *
 * macOS has to read a keychain item and fall back to a file. Windows has no
 * keychain in this path at all: probed on a live install, `cmdkey /list` names
 * no Claude entry, and the credential is a plaintext `.credentials.json` in the
 * config directory. So this is one file read, on both the native and the WSL
 * path.
 *
 * The refresh token is deliberately withheld, exactly as on macOS. OAuth refresh
 * tokens are single-use, so a client that rotated a chain shared with Claude
 * Code would strand whichever copy lost the race. Claude Code keeps the
 * credential fresh; Battery only ever reads it.
 */
sealed interface CredentialLookup {

    /** A usable access token. */
    data class Available(val tokens: StoredTokens) : CredentialLookup

    /**
     * Mapped, but not readable right now.
     *
     * **Never falls through to a local token store.** That fall-through is the
     * bug `32b7451` fixed on macOS: it produced a sign-in prompt for an account
     * that already had a working grant, minting a second refresh chain and
     * stranding one of the two. On Windows this state is not the rare accident
     * it is on a Mac — [Reason.DISTRO_NOT_RUNNING] is a daily, correct,
     * entirely un-alarming fact about the machine — which makes failing closed
     * matter more here, not less.
     */
    data class Unavailable(val reason: Reason, val detail: String? = null) : CredentialLookup

    enum class Reason {
        /** The distro is shut down. Ordinary. Not an error, and not the user's problem to fix. */
        DISTRO_NOT_RUNNING,

        /** No credential where one was expected — Claude Code signed out, or never signed in. */
        FILE_MISSING,

        /** Present but unreadable: permissions, a half-written file, a dead 9P mount. */
        UNREADABLE,

        /** Read, but not the shape Claude Code writes. The format moved. */
        MALFORMED,

        /**
         * Read and valid, but the access token has expired.
         *
         * Battery cannot fix this: it holds no refresh token by design. Claude
         * Code refreshes on its next run, so the honest message names that
         * rather than offering a sign-in that would make things worse.
         */
        EXPIRED,
    }

    /** What to tell the user. Phrased as a fact about their machine, not an error. */
    val message: String?
        get() = when (this) {
            is Available -> null
            is Unavailable -> when (reason) {
                Reason.DISTRO_NOT_RUNNING -> "${detail ?: "The distro"} isn't running"
                Reason.FILE_MISSING -> "Claude Code isn't signed in here"
                Reason.UNREADABLE -> "Claude Code's credential couldn't be read"
                Reason.MALFORMED -> "Claude Code's credential format has changed"
                Reason.EXPIRED -> "Claude Code's token has expired — it refreshes on its next run"
            }
        }
}

object CredentialBridge {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Read the credential for [dir], starting a distro for nobody.
     *
     * The running check comes first and short-circuits: for a WSL directory,
     * merely *stat*ing the credential path would boot the distro, so the answer
     * has to be known before the path is touched.
     */
    fun read(
        dir: ClaudeDir,
        command: WslCommand = RealWslCommand,
        readFile: (String) -> String? = {
            runCatching { File(ClaudeConfigDir.onThisPlatform(it)).readText() }.getOrNull()
        },
        nowMillis: Long = System.currentTimeMillis(),
    ): CredentialLookup {
        if (dir.origin == DirOrigin.WSL) {
            val distro = dir.distro
            if (distro == null || distro !in Wsl.running(command)) {
                return CredentialLookup.Unavailable(
                    CredentialLookup.Reason.DISTRO_NOT_RUNNING,
                    distro,
                )
            }
        }

        val path = ClaudeConfigDir.credentialsFile(dir.path)
        val raw = readFile(path)
            ?: return CredentialLookup.Unavailable(CredentialLookup.Reason.FILE_MISSING, path)
        val tokens = parse(raw)
            ?: return CredentialLookup.Unavailable(CredentialLookup.Reason.MALFORMED, path)
        if (tokens.expiresAt in 1..nowMillis) {
            return CredentialLookup.Unavailable(CredentialLookup.Reason.EXPIRED, path)
        }
        return CredentialLookup.Available(tokens)
    }

    /**
     * Claude Code's credential blob to tokens, or null if it isn't one.
     *
     * Split out because this is the part that breaks silently: the shape is
     * undocumented and belongs to another program, so a change there turns every
     * bridged account into a null that used to be indistinguishable from "not
     * bridged". Pinned by a test against the real shape, which a live install
     * confirms is `claudeAiOauth.{accessToken, refreshToken, expiresAt}` with
     * `expiresAt` in epoch **milliseconds**.
     */
    fun parse(raw: String): StoredTokens? = try {
        val oauth = json.parseToJsonElement(raw).jsonObject["claudeAiOauth"]?.jsonObject
        val access = oauth?.get("accessToken")?.jsonPrimitive?.content
        if (access.isNullOrEmpty()) {
            null
        } else {
            StoredTokens(
                accessToken = access,
                // Withheld on purpose. See the file doc: sharing a single-use
                // refresh chain with Claude Code strands one of the two copies.
                refreshToken = null,
                expiresAt = oauth["expiresAt"]?.jsonPrimitive?.long ?: 0L,
            )
        }
    } catch (_: Exception) {
        null
    }
}
