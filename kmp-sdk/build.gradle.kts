plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

// :kmp-sdk — the real, publishable KMP library other Compose Multiplatform / KMP customers add as
// a Gradle dependency to get the full Encatch API with zero bridging code of their own.
//
// Unlike compose-sample/kmp-sample (which are apps that themselves produce an XCFramework for an
// Xcode project to embed), :kmp-sdk is consumed via a normal Gradle project dependency by other
// Kotlin/Native modules (compose-sample/kmp-sample today, any future KMP consumer tomorrow), which
// then produce their own framework with this module linked in transitively. So there is no
// `XCFramework(...)`/`binaries.framework { xcf.add(this) }` here — just a plain (non-framework)
// `iosArm64()`/`iosSimulatorArm64()` klib output, matching how any ordinary KMP library module
// (e.g. `:core`) is shaped.
//
// iOS cinterops directly against ios-native/'s pure-Swift SDK via the @objc facade in
// ios-native/Sources/Encatch/ObjCBridge/EncatchBridge.swift, the same way compose-sample/kmp-sample
// do — this module needs its own copy of that cinterop wiring since it's the one whose Kotlin code
// actually calls across the interop boundary on iOS.
val iosNativeDistDir = rootProject.layout.projectDirectory.dir("ios-native/dist")

// See compose-sample/build.gradle.kts's twin comment for the full rationale — this reproduces
// ios-native/dist/ (cinterop static lib + ObjC header) via ios-native/build-dist.sh so a fresh
// checkout doesn't need someone to hand-run the xcodebuild/libtool/lipo dance.
val buildIosNativeDist by tasks.registering(Exec::class) {
    description = "Builds ios-native/dist/ (cinterop static lib + ObjC header) via ios-native/build-dist.sh"
    workingDir(rootProject.layout.projectDirectory.dir("ios-native"))
    commandLine("./build-dist.sh")
    inputs.dir(rootProject.layout.projectDirectory.dir("ios-native/Sources"))
    inputs.file(rootProject.layout.projectDirectory.file("ios-native/build-dist.sh"))
    outputs.dir(iosNativeDistDir)
}

kotlin {
    // See :core's build.gradle.kts for the full rationale — pinned below our actual Kotlin
    // version so consumers on an older Kotlin Gradle Plugin can still read this published
    // library's binary metadata. Especially relevant here: :kmp-sdk is the module most likely to
    // be depended on directly by a customer's own KMP app.
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }

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
                    // and static-library path for ios-native/dist/ are supplied here per-target.
                    val dist = rootProject.layout.projectDirectory
                        .dir("ios-native/dist/ios-arm64").asFile.absolutePath
                    compilerOpts("-I$dist")
                    extraOpts("-libraryPath", dist)
                }
            }
        }
    }
    iosSimulatorArm64 {
        compilations.getByName("main") {
            cinterops {
                create("EncatchBridge") {
                    defFile(project.file("src/nativeInterop/cinterop/EncatchBridge.def"))
                    packageName("com.encatch.bridge")
                    // The .def file can't express repo-relative paths, so the header include dir
                    // and static-library path for ios-native/dist/ are supplied here per-target.
                    val dist = rootProject.layout.projectDirectory
                        .dir("ios-native/dist/sim-arm64").asFile.absolutePath
                    compilerOpts("-I$dist")
                    extraOpts("-libraryPath", dist)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val androidMain by getting {
            dependencies {
                implementation(project(":core"))
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
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
    dependsOn(buildIosNativeDist)
}

android {
    namespace = "com.encatch.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("Encatch KMP SDK")
        description.set("Kotlin Multiplatform bridge to the native Encatch SDKs (Android + iOS) — full API, no UI.")
        url.set("https://github.com/get-encatch/encatch-android")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                name.set("Encatch")
                url.set("https://encatch.com")
            }
        }
        scm {
            url.set("https://github.com/get-encatch/encatch-android")
            connection.set("scm:git:https://github.com/get-encatch/encatch-android.git")
        }
    }
}
