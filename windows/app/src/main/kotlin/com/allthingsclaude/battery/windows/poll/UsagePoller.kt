package com.allthingsclaude.battery.windows.poll

import com.allthingsclaude.battery.core.AppConfig
import com.allthingsclaude.battery.core.UsageApi
import com.allthingsclaude.battery.core.UsageApiError
import com.allthingsclaude.battery.core.UsageResponse
import com.allthingsclaude.battery.windows.auth.CredentialLookup
import com.allthingsclaude.battery.windows.auth.TokenCache
import com.allthingsclaude.battery.windows.config.ClaudeDir

/** The outcome of one poll. */
sealed interface PollResult {
    data class Ok(val usage: UsageResponse) : PollResult

    /** No token to try with. Carries the reason so the UI can say something true. */
    data class Blocked(val lookup: CredentialLookup.Unavailable) : PollResult

    /** A token was tried and the API refused or the network failed. */
    data class Failed(val error: UsageApiError) : PollResult
}

/**
 * One poll, from directory to figures.
 *
 * **Never calls `UsageApi.fetchUsage`.** That method refreshes when the token is
 * close to expiry, and refreshing is precisely what a bridged client must not
 * do: the credential is Claude Code's, the refresh token is deliberately
 * withheld ([com.allthingsclaude.battery.windows.auth.CredentialBridge]), and
 * rotating a single-use chain shared with another program strands whichever copy
 * loses. `requestUsage` sends the bearer token and nothing else. When it goes
 * stale, the answer is to read the file again — Claude Code will have refreshed
 * it — which is [TokenCache]'s job, not this one's.
 */
class UsagePoller(
    private val api: UsageApi = UsageApi(AppConfig.userAgent(VERSION)),
    private val cache: TokenCache = TokenCache(),
) {
    fun poll(dir: ClaudeDir): PollResult =
        when (val lookup = cache.tokens(dir)) {
            is CredentialLookup.Unavailable -> PollResult.Blocked(lookup)
            is CredentialLookup.Available -> try {
                PollResult.Ok(api.requestUsage(lookup.tokens.accessToken))
            } catch (e: UsageApiError) {
                // A 401 against a credential Battery does not own means Claude
                // Code's token moved on. Drop the cache so the next poll reads
                // the file rather than replaying a token the server has retired.
                if (e is UsageApiError.Unauthorized) cache.clear()
                PollResult.Failed(e)
            }
        }

    companion object {
        /** Until there is a release pipeline. Phase 5 replaces this. */
        const val VERSION = "0.1.0-dev"
    }
}
