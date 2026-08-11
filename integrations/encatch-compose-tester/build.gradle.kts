import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Standalone tester app for :compose-sdk — the simplest of the four testers, since one
// commonMain Compose UI (App.kt) drives both platforms directly via ComposeUIViewController
// (iOS) / setContent (Android). No cinterop block, no swift/dist step: :compose-sdk's own
// cinterop klib is linked transitively, same as compose-sample (see its build.gradle.kts).
val xcf = XCFramework("EncatchComposeTester")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    iosArm64 {
        binaries.framework {
            baseName = "EncatchComposeTester"
            isStatic = true
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "EncatchComposeTester"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":compose-sdk"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.serialization.json)
        }
        val androidMain by getting {
            dependencies {
                // Installs the modal form host eagerly at app startup (see MainActivity.kt) so
                // "Show form (modal)" works from Home without requiring the tester to visit
                // Inline first — EncatchInlineForm's own lazy LaunchedEffect install would
                // otherwise only run once that composable enters composition.
                implementation(project(":android"))
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
            }
        }
        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
    }
}

android {
    namespace = "com.encatch.composetester"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.encatch.composetester"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    lint {
        // lintVitalAnalyzeRelease crashes with an internal lint failure on this CMP module
        // ("this is a bug in lint or one of the libraries it depends on") — skip the release
        // lint gate; this is an internal tester app, not a Play Store artifact.
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-keystore signing so internal testers can install the release APK directly —
            // this is a tester app, not a Play Store artifact.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
