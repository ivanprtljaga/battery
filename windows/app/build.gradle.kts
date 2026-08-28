plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Phase 0/1 is a console process on purpose: every genuinely risky thing about
// this port — WSL path resolution, the credential bridge, not booting a distro
// by accident — is answerable without a window on screen. Compose Desktop
// arrives in Phase 2, when there is something worth drawing.

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

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.allthingsclaude.battery.windows.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}
