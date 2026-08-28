package com.allthingsclaude.battery.windows.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant

/**
 * The few fields of a transcript line that anyone here cares about.
 *
 * A line in `projects/<dir>/<session>.jsonl` carries fifteen or more keys and
 * can be tens of kilobytes of tool output; three of them matter.
 *
 * Read through the element API rather than declared as a `@Serializable` class,
 * because `:app` deliberately does not apply the serialization compiler plugin —
 * the same runtime-only use `core` makes of this dependency, and the reason the
 * borrowed-module contract in `build.gradle.kts` still holds.
 */
internal data class TranscriptLine(
    val timestamp: Instant,
    val cwd: String?,
    val tokens: Long,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parse one line, or null for anything that is not a usage-bearing entry.
         *
         * Every failure is a null rather than an exception. These files belong to
         * another program and are appended to while this reads them, so a torn
         * last line is an expected event rather than corruption — dropping it
         * costs one message and keeps the other thousand.
         */
        fun parse(line: String): TranscriptLine? = try {
            val root = json.parseToJsonElement(line).jsonObject
            val usage = root["message"]?.jsonObject?.get("usage")?.jsonObject
            val at = root["timestamp"]?.jsonPrimitive?.content
            if (usage == null || at == null) {
                null
            } else {
                val tokens = totalTokens(usage)
                if (tokens <= 0) {
                    null
                } else {
                    TranscriptLine(Instant.parse(at), root["cwd"]?.jsonPrimitive?.content, tokens)
                }
            }
        } catch (_: Exception) {
            null
        }

        /**
         * All four counters, summed — matching `StatsCacheService.tokenCount`
         * exactly, so a project's figure means the same thing on both platforms.
         * Cache reads dominate the total on a long session, which is a fair
         * description of what the window was actually spent on.
         */
        private fun totalTokens(usage: JsonObject): Long =
            count(usage, "input_tokens") +
                count(usage, "output_tokens") +
                count(usage, "cache_creation_input_tokens") +
                count(usage, "cache_read_input_tokens")

        private fun count(usage: JsonObject, key: String): Long =
            runCatching { usage[key]?.jsonPrimitive?.long ?: 0L }.getOrDefault(0L)
    }
}
