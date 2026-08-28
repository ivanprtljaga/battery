import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.imageio.ImageIO

/**
 * Which Skia native build this machine needs.
 *
 * Resolved from the host rather than pinned, so `gradlew.bat run` on Windows
 * gets the Windows binary and development on Linux gets the Linux one from the
 * same build file. Packaging is host-only anyway — jpackage cannot cross-build —
 * so host detection is the correct rule here, not a compromise.
 */
val composeHostTarget: String by lazy {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it.startsWith("arm") }
    when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") || os.contains("darwin") -> if (arm) "macos-arm64" else "macos-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// No `application` plugin: `compose.desktop.application` registers its own `run`
// and the two collide. Everything that block used to hold — the main class, the
// console encoding — moves into the compose block below.
//
// The console poller from Phase 1 survives as `--headless`. It is the only mode
// that runs anywhere but Windows, which makes it the fastest way to separate a
// UI bug from a data bug.

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The Android module, borrowed in place. See settings.gradle.kts.
    implementation(project(":core"))

    // `core` keeps this as `implementation`, so it is not on our compile
    // classpath transitively — declared here for the config and credential
    // parsing. Same runtime-only usage as core: no @Serializable types, no
    // compiler plugin.
    implementation(libs.kotlinx.serialization.json)

    // No Material3: a tray flyout needs none of it, and this file sets its own
    // type and colour anyway. `BasicText` from foundation is the whole
    // requirement, and skipping Material keeps the packaged app smaller.
    //
    // The artifact is host-specific because Skia ships as a native library:
    // plain `desktop-jvm` carries no `libskiko`, and the failure is at runtime
    // ("Cannot find libskiko-linux-x64.so"), not at resolution. This is what the
    // removed `compose.desktop.currentOS` accessor used to do.
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$composeHostTarget:${libs.versions.compose.get()}")

    // The Win32 calls the JDK does not expose. `Shell_NotifyIconGetRect` is the
    // whole of it today: AWT can put an icon in the notification area but will
    // not say where the shell put it, and a flyout that guesses is a flyout
    // that lands in the wrong place on a centred or repositioned taskbar.
    //
    // Pure Java with a bundled native stub, so it does not change what jpackage
    // has to ship. Phases 4 and 5 already have it on their list for
    // ITaskbarList3 and the toast surface.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
}


// ---------------------------------------------------------------- packaging

/**
 * The one place a version number lives.
 *
 * Phase 1 left `UsagePoller.VERSION` as a literal with a note that the release
 * pipeline would replace it. This is that replacement: the MSI's ProductVersion,
 * the User-Agent the API sees, and the figure the updater compares against are
 * now the same string by construction rather than by remembering.
 *
 * Plain `major.minor.patch` with no suffix, because Windows Installer will not
 * take anything else — an MSI ProductVersion is three numbers and nothing more.
 */
version = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"

/**
 * Which repository this build looks in for its own updates.
 *
 * Derived from the repository being built rather than hardcoded, which is the
 * lesson `android-release.yml` already learned and wrote down: a fork that polls
 * upstream compares upstream's newest tag against its own version and reports
 * "up to date" for ever — indistinguishable, from the outside, from an updater
 * that works.
 */
val releaseRepo = (findProperty("releaseRepo") as String?)?.takeIf { it.isNotBlank() }
    ?: "allthingsclaude/battery"

/**
 * `BuildInfo.kt`, generated.
 *
 * A resource read at runtime would have been the other option and is worse
 * here: a jpackage app-image is not a jar, so `Package.getImplementationVersion`
 * is null in exactly the build that matters most.
 */
val generateBuildInfo by tasks.registering {
    val output = layout.buildDirectory.dir("generated/buildinfo")
    val appVersion = version.toString()
    val repo = releaseRepo
    inputs.property("version", appVersion)
    inputs.property("repo", repo)
    outputs.dir(output)
    doLast {
        val dir = output.get().asFile.resolve("com/allthingsclaude/battery/windows")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            """
            package com.allthingsclaude.battery.windows

            /** Generated by the build. See `generateBuildInfo` in build.gradle.kts. */
            object BuildInfo {
                const val VERSION = "$appVersion"

                /** Where the updater looks. See `releaseRepo` in build.gradle.kts. */
                const val RELEASE_REPO = "$repo"
            }

            """.trimIndent(),
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateBuildInfo)
}

/**
 * The Windows icon, rendered from the shared brand mark.
 *
 * `../assets/icon.png` is the same 2048px source every other platform starts
 * from, so the Windows icon cannot drift from the Mac and Android ones by being
 * a separate file somebody forgot to update. An `.ico` is a container: this
 * writes the modern PNG-payload form, which Windows Vista and later read, at the
 * sizes the shell actually asks for.
 */
val windowsIcon by tasks.registering {
    val source = rootProject.layout.projectDirectory.file("../assets/icon.png")
    val target = layout.buildDirectory.file("icon/battery.ico")
    inputs.file(source)
    outputs.file(target)
    doLast {
        val sizes = listOf(16, 20, 24, 32, 48, 64, 128, 256)
        val original = ImageIO.read(source.asFile)
        val encoded = sizes.map { size ->
            val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(original, 0, 0, size, size, null)
            g.dispose()
            val bytes = ByteArrayOutputStream()
            ImageIO.write(scaled, "png", bytes)
            size to bytes.toByteArray()
        }

        val file = target.get().asFile
        file.parentFile.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun short(value: Int) = out.write(byteArrayOf((value and 0xFF).toByte(), (value shr 8 and 0xFF).toByte()))
            fun int(value: Int) = out.write(
                byteArrayOf(
                    (value and 0xFF).toByte(),
                    (value shr 8 and 0xFF).toByte(),
                    (value shr 16 and 0xFF).toByte(),
                    (value shr 24 and 0xFF).toByte(),
                ),
            )
            short(0); short(1); short(encoded.size)          // ICONDIR
            var offset = 6 + 16 * encoded.size
            encoded.forEach { (size, bytes) ->
                // 256 is written as 0: the field is one byte, so the largest
                // size an icon directory can name is spelled by wrapping it.
                out.write(if (size >= 256) 0 else size)
                out.write(if (size >= 256) 0 else size)
                out.write(0); out.write(0)                    // palette, reserved
                short(1); short(32)                           // planes, bpp
                int(bytes.size); int(offset)
                offset += bytes.size
            }
            encoded.forEach { (_, bytes) -> out.write(bytes) }
        }
    }
}

// jpackage reads the icon off disk rather than through a provider, so the
// dependency has to be stated rather than inferred from `iconFile`.
tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") }
    .configureEach { dependsOn(windowsIcon) }

compose.desktop {
    application {
        mainClass = "com.allthingsclaude.battery.windows.AppKt"

        // The Windows console runs a legacy code page, so anything outside it —
        // every em dash in this app's output — arrives as `?`. Observed on a
        // real run. These two properties are JDK 19+ and set the console streams
        // specifically; `file.encoding` alone does not cover stdout.
        jvmArgs += listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

        nativeDistributions {
            // Msi is what winget installs and what the release publishes. Exe is
            // built alongside because jpackage gives it for the same WiX
            // dependency, and a double-clickable installer is what somebody
            // arriving from the README expects.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Battery"
            packageVersion = project.version.toString()
            // Not decoration: jpackage writes this into the executable's
            // FileDescription resource, and Windows reads *that* to head every
            // toast and to group them in the notification centre. Short enough
            // not to be truncated there, and qualified rather than just
            // "Battery", which sits confusingly close to Windows' own power
            // notifications.
            description = "Battery for Claude Code"
            copyright = "© allthingsclaude"
            vendor = "allthingsclaude"

            // The app is a tray icon and a Skia surface. It needs a JVM, a
            // desktop toolkit, and the network — not the compiler, not JDBC,
            // not the sound engine.
            //
            // `jdk.localedata` is the one that is easy to leave out and wrong to.
            // Since Java 9 `java.base` carries only the root and English locale
            // data; everything else lives in this module. Without it the panel
            // renders a reset time as "Wed 7:00 PM" for a user whose Windows
            // clock says 18:59 — and it would do it only in the packaged build,
            // which is the build nobody runs while developing.
            modules("java.desktop", "java.net.http", "jdk.crypto.ec", "jdk.localedata")

            windows {
                // The Start Menu entry is not decoration. A toast is attributed
                // to its process's AppUserModelID, and Windows resolves that ID
                // to a name and an icon by looking for a Start Menu shortcut
                // that carries it. Without `menu = true` the app announces
                // itself by its raw identifier for ever. See Toaster.
                menu = true
                menuGroup = "Battery"
                shortcut = true
                dirChooser = true

                // Per-user, so installing never needs an administrator. A tray
                // app that reads one person's Claude Code credential has no
                // business in Program Files or in another account's session.
                perUserInstall = true

                // Fixed for the life of the product: this is what tells Windows
                // Installer that 0.2.0 replaces 0.1.0 rather than sitting beside
                // it. Generated once and never regenerated.
                upgradeUuid = "6F1D3B2A-9C47-4E58-A0D1-7B2E5C84F930"

                iconFile.set(layout.buildDirectory.file("icon/battery.ico"))
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}
