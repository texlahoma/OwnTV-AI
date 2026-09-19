// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Applied by :core (and :player-core in Phase 8). Declared here, not in the module, because
    // com.android.library ships inside AGP and is already on the build classpath by the time a
    // module asks for it by version — Gradle then refuses to version-check it.
    alias(libs.plugins.android.library) apply false
    // Kotlin comes from AGP 9's built-in Kotlin support. Compose compiler is pinned to that Kotlin
    // version; KSP 2.3.6+ supports built-in Kotlin, so no kotlin-android plugin is needed.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    // Baseline profiles (audit ST1) — applied by :app and :baselineprofile.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
