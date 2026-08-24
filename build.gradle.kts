plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Declared at the root (apply false) so every publishing module shares one plugin
    // classloader — per-module-only application puts the plugin's shared build service in
    // different classloader scopes and publishToMavenCentral fails wiring it up.
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "com.encatch"
    // Pre-1.0 like the Swift SDK (encatch-swift): 0.x signals the API can still move.
    version = "0.1.1"
}
