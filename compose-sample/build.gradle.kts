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
// iOS no longer links :core / :ios-native-form-ui (Kotlin/Native). It cinterops directly against
// ios-native/'s pure-Swift SDK via the @objc facade in
// ios-native/Sources/Encatch/ObjCBridge/EncatchBridge.swift — see
// /Users/godwin/.claude/plans/stateless-floating-ripple.md. The compiled artifacts (static lib +
// generated ObjC header) that cinterop consumes are pre-built by hand into
// ios-native/dist/{ios-arm64,sim-arm64}/ via `xcodebuild build -scheme Encatch -destination ...`
// (SPM alone doesn't emit an ObjC header/static-lib layout cinterop can use).
val xcf = XCFramework("EncatchComposeSample")

// ios-arm64 = device, sim-arm64 = Simulator (Apple Silicon host) — see ios-native/dist's own
// build steps for how these were produced.
val iosNativeDistDir = rootProject.layout.projectDirectory.dir("ios-native/dist")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    iosArm64 {
        compilations.getByName("main") {
            cinterops {
                create("EncatchBridge") {
                    defFile(project.file("src/nativeInterop/cinterop/EncatchBridge.def"))
                    packageName("com.encatch.bridge")
                }
            }
        }
        binaries.framework {
            baseName = "EncatchComposeSample"
            isStatic = true
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        compilations.getByName("main") {
            cinterops {
                create("EncatchBridge") {
                    defFile(project.file("src/nativeInterop/cinterop/EncatchBridge.def"))
                    packageName("com.encatch.bridge")
                }
            }
        }
        binaries.framework {
            baseName = "EncatchComposeSample"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        val androidMain by getting {
            dependencies {
                implementation(project(":core"))
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
