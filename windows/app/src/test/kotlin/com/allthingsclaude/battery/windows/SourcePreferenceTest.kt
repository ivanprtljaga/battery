package com.allthingsclaude.battery.windows

import com.allthingsclaude.battery.windows.config.ClaudeDir
import com.allthingsclaude.battery.windows.config.DirOrigin
import com.allthingsclaude.battery.windows.config.SourcePreference
import com.allthingsclaude.battery.windows.poll.PollResult
import com.allthingsclaude.battery.windows.auth.CredentialLookup
import com.allthingsclaude.battery.windows.state.AppState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcePreferenceTest {

    private val file: File = File.createTempFile("battery-source", ".tsv").also { it.delete() }

    @AfterTest
    fun cleanUp() {
        file.delete()
    }

    private val ubuntu = ClaudeDir(
        path = "\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.claude",
        origin = DirOrigin.WSL,
        distro = "Ubuntu",
    )
    private val native = ClaudeDir(path = "C:\\Users\\Ivan\\.claude", origin = DirOrigin.WINDOWS_NATIVE)

    /**
     * The one that matters. A pin that came back as a bare path would be an
     * [DirOrigin.EXPLICIT] directory, which `CredentialBridge` does not
     * distro-gate — so reading it with the distro down would *start* the distro.
     * Round-tripping the origin is what keeps the gate.
     */
    @Test
    fun `a WSL pin comes back gateable`() {
        SourcePreference.save(ubuntu, file)
        val loaded = SourcePreference.load(file)

        assertEquals(DirOrigin.WSL, loaded?.origin, "not EXPLICIT, or the gate is gone")
        assertEquals("Ubuntu", loaded?.distro, "the gate needs a distro to ask about")
        assertEquals(ubuntu.path, loaded?.path)
    }

    @Test
    fun `a UNC path survives the round trip unescaped`() {
        SourcePreference.save(ubuntu, file)
        assertTrue(file.readText().contains("\\\\wsl.localhost\\Ubuntu"), file.readText())
        assertEquals(ubuntu, SourcePreference.load(file))
    }

    @Test
    fun `a native pin has no distro`() {
        SourcePreference.save(native, file)
        assertEquals(native, SourcePreference.load(file))
        assertNull(SourcePreference.load(file)?.distro)
    }

    @Test
    fun `no file is no preference`() {
        assertNull(SourcePreference.load(file))
    }

    @Test
    fun `a corrupt file is no preference, not a crash`() {
        file.writeText("nonsense")
        assertNull(SourcePreference.load(file))
        file.writeText("NOT_AN_ORIGIN\tUbuntu\tC:\\x")
        assertNull(SourcePreference.load(file))
    }

    @Test
    fun `clearing goes back to choosing automatically`() {
        SourcePreference.save(ubuntu, file)
        SourcePreference.clear(file)
        assertNull(SourcePreference.load(file))
    }

    /**
     * A pin outranks the candidate list even when it is not in it — a shut-down
     * distro must say so rather than hand the question to the other install,
     * which would answer with a different session window under a label the user
     * never chose.
     */
    @Test
    fun `a pinned distro that is not running is still the source`() {
        SourcePreference.save(ubuntu, file)
        val state = AppState(
            poll = {
                PollResult.Blocked(
                    CredentialLookup.Unavailable(CredentialLookup.Reason.DISTRO_NOT_RUNNING, "Ubuntu"),
                )
            },
            runningDistros = { emptyList() },
            candidates = { listOf(native) },
            preferenceFile = file,
        )

        state.resolve()

        assertEquals(ubuntu, state.dir, "the pin wins over the only live candidate")
        assertEquals("WSL: Ubuntu", state.sourceLabel)
        assertEquals(listOf(native), state.sources, "and the menu still offers the alternative")
    }

    @Test
    fun `with no pin the live candidate is chosen`() {
        val state = AppState(
            poll = { PollResult.Blocked(CredentialLookup.Unavailable(CredentialLookup.Reason.FILE_MISSING)) },
            runningDistros = { emptyList() },
            candidates = { listOf(native) },
            preferenceFile = file,
        )

        state.resolve()

        assertEquals(native, state.dir)
    }

    @Test
    fun `selecting a source pins it and drops the other install's figures`() {
        val state = AppState(
            poll = {
                PollResult.Blocked(
                    CredentialLookup.Unavailable(CredentialLookup.Reason.DISTRO_NOT_RUNNING, "Ubuntu"),
                )
            },
            runningDistros = { emptyList() },
            candidates = { listOf(native, ubuntu) },
            preferenceFile = file,
        )
        state.resolve()

        state.select(ubuntu)

        assertEquals(ubuntu, state.dir)
        assertEquals(ubuntu, SourcePreference.load(file), "and it survives a restart")
        assertNull(state.usage, "figures about the other install are not figures about this one")
    }
}
