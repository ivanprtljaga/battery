plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Phase 2. Declared so the version resolves in one place, applied by :app
    // only once there is a UI to compile.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}
