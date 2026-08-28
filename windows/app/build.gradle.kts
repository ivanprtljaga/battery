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
    application
}

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

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.allthingsclaude.battery.windows.AppKt")
}

tasks.named<JavaExec>("run") {
    // The Windows console runs a legacy code page, so anything outside it —
    // every em dash in this app's output — arrives as `?`. Observed on a real
    // run. These two properties are JDK 19+ and set the console streams
    // specifically; `file.encoding` alone does not cover stdout.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}
