// Battery for Windows — see README.md
//
// Two modules, mirroring the Android split:
//   core  — NOT a copy. This is `../android/core`, the same directory, mounted
//           into this build by `projectDir` below. See "Sharing core" in the
//           README for why it is borrowed rather than moved.
//   app   — everything Windows: the Compose Desktop UI, the tray icon, the WSL
//           config resolver, and the poll loop.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Compose Multiplatform's Gradle plugin is not on the plugin portal.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Not a mistake, and not an Android dependency: Compose Multiplatform
        // 1.12 resolves parts of `androidx.lifecycle` and `androidx.savedstate`
        // that are published only to Google's Maven. Nothing here applies AGP or
        // needs an SDK — these are plain JVM artifacts that happen to live there.
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "BatteryWindows"

include(":core")
include(":app")

// `core` lives in the Android tree and is read from there in place.
//
// Deliberately NOT `includeBuild("../android")`. A composite build has to
// evaluate the included build's settings, which applies AGP and therefore
// demands an Android SDK — on a Windows machine that only wants to build the
// desktop app, that is a hard failure for no benefit. Redirecting `projectDir`
// borrows the one module we want and never mentions the Android plugin.
//
// This works because `../android/core/build.gradle.kts` is honestly
// Android-free: `kotlin-jvm`, one runtime JSON dependency, nothing else. That
// file is the contract, and it is the thing to check if this ever breaks.
project(":core").projectDir = file("../android/core")
