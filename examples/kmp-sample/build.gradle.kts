import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// Variant 5: a sample app that is itself a Kotlin Multiplatform project (shared commonMain
// business logic calling the real :kmp-sdk library directly), rather than a single-platform app
// that merely depends on KMP :core (variants 1-4). Validates the real Gradle KMP consumption path
// for :kmp-sdk on both Android (thin forward to :core) and iOS (Kotlin/Native cinterop onto
// swift/'s pure-Swift SDK).
//
// Unlike compose-sample, this module DOES keep its own `cinterops` block onto swift/'s @objc
// facade: it has no Compose UI, so it can't use :compose-sdk's `EncatchInlineForm` composable, and
// :kmp-sdk deliberately doesn't expose the raw `EncatchInlineFormView` type itself (it's a pure
// business-logic module, no UI surface at all — see kmp-sdk/build.gradle.kts's comment). So
// `KmpSampleViewController.kt`'s hand-rolled UIKit screen still needs direct cinterop access to
// `com.encatch.bridge.EncatchInlineFormView` for the inline-form *view* only — all business logic
// (`Encatch.init`/`showForm`/etc., including automatic modal-form-host install) goes through
// `:kmp-sdk`'s `Encatch` now, not this module's own bridge calls. This is a known, flagged gap in
// :kmp-sdk's surface for non-Compose KMP consumers that need a raw inline-form view; a cleaner fix
// would be a small non-Compose `expect`/`actual` view accessor in :kmp-sdk itself, deferred here.
val xcf = XCFramework("EncatchKmpSample")

// ios-arm64 = device, sim-arm64 = Simulator (Apple Silicon host) — see swift/dist's own
// build steps for how these were produced.
val swiftDistDir = rootProject.layout.projectDirectory.dir("swift/dist")

// Automates the above: runs swift/build-dist.sh, which reproduces dist/{ios-arm64,sim-arm64}/
// from swift/Sources/ via `xcodebuild` + `libtool`/`lipo` (see that script's own header
// comment for the exact recipe). The script hashes swift/Sources/ into dist/.build-stamp and
// no-ops if nothing changed, so wiring it as a hard `dependsOn` on every cinterop task doesn't
// force an xcodebuild invocation on every Gradle build — Gradle's own `inputs`/`outputs` below add
// a second (even cheaper) up-to-date check on top of that.
val buildSwiftDist by tasks.registering(Exec::class) {
    description = "Builds swift/dist/ (cinterop static lib + ObjC header) via swift/build-dist.sh"
    workingDir(rootProject.layout.projectDirectory.dir("swift"))
    commandLine("./build-dist.sh")
    inputs.dir(rootProject.layout.projectDirectory.dir("swift/Sources"))
    inputs.file(rootProject.layout.projectDirectory.file("swift/build-dist.sh"))
    outputs.dir(swiftDistDir)
}

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
                    // The .def file can't express repo-relative paths, so the header include dir
                    // and static-library path for swift/dist/ are supplied here per-target.
                    val dist = rootProject.layout.projectDirectory
                        .dir("swift/dist/ios-arm64").asFile.absolutePath
                    compilerOpts("-I$dist")
                    extraOpts("-libraryPath", dist)
                }
            }
        }
        binaries.framework {
            baseName = "EncatchKmpSample"
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
                    // The .def file can't express repo-relative paths, so the header include dir
                    // and static-library path for swift/dist/ are supplied here per-target.
                    val dist = rootProject.layout.projectDirectory
                        .dir("swift/dist/sim-arm64").asFile.absolutePath
                    compilerOpts("-I$dist")
                    extraOpts("-libraryPath", dist)
                }
            }
        }
        binaries.framework {
            baseName = "EncatchKmpSample"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kmp-sdk"))
        }
        val androidMain by getting {
            dependencies {
                // :android (not :core directly) — needed for the raw EncatchInlineFormView type
                // KmpSampleMainActivity embeds; business logic goes through :kmp-sdk, which itself
                // forwards to :core.
                implementation(project(":android"))
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.appcompat)
                implementation(libs.kotlinx.coroutines.core)
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

// The generated task names are cinteropEncatchBridge<Target> (e.g. cinteropEncatchBridgeIosArm64 /
// cinteropEncatchBridgeIosSimulatorArm64) — match by prefix rather than hardcoding both, so this
// keeps working if/when more iOS targets are added.
tasks.matching { it.name.startsWith("cinteropEncatchBridge") }.configureEach {
    dependsOn(buildSwiftDist)
}

android {
    namespace = "com.encatch.kmpsample"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
