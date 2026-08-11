import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Variant 4: Compose Multiplatform host (Android + iOS) wrapping the SDK's existing native UI
// components via interop (AndroidView / UIKitView) — no WebView reimplementation, no new
// third-party dependency. Produces EncatchComposeSample.xcframework for the ios-sample app to embed.
//
// This module is a thin CONSUMER of :compose-sdk (which itself depends on :kmp-sdk) — it has no
// cinterop block of its own, no com.encatch.bridge.* usage, and no swift/dist/ build step:
// :compose-sdk's own cinterop klib (against swift/'s pure-Swift SDK via the @objc facade in
// swift/Sources/Encatch/ObjCBridge/EncatchBridge.swift) is linked transitively into this
// module's iOS binaries.framework output.
val xcf = XCFramework("EncatchComposeSample")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    iosArm64 {
        binaries.framework {
            baseName = "EncatchComposeSample"
            isStatic = true
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "EncatchComposeSample"
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
        }
        val androidMain by getting {
            dependencies {
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
    namespace = "com.encatch.composesample"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
