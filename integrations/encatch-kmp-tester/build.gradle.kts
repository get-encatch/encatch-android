import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// Standalone tester app for :kmp-sdk — same shared-commonMain-drives-two-native-UIs shape as
// kmp-sample, but as its own installable/distributable app (kotlin-multiplatform + android
// *application*, not android-library) with a runtime Setup screen, matching
// encatch-android-tester/encatch-ios-tester. See kmp-sample/build.gradle.kts for the full
// rationale behind the cinterop block below — the same known gap applies here: :kmp-sdk has no
// Compose dependency and doesn't expose the raw EncatchInlineFormView type itself, so this module
// keeps its own cinterop onto ios-native/'s @objc facade for the inline-form *view* only. All
// business logic goes through :kmp-sdk's Encatch (see TesterController.kt).
val xcf = XCFramework("EncatchKmpTester")

val iosNativeDistDir = rootProject.layout.projectDirectory.dir("ios-native/dist")

val buildIosNativeDist by tasks.registering(Exec::class) {
    description = "Builds ios-native/dist/ (cinterop static lib + ObjC header) via ios-native/build-dist.sh"
    workingDir(rootProject.layout.projectDirectory.dir("ios-native"))
    commandLine("./build-dist.sh")
    inputs.dir(rootProject.layout.projectDirectory.dir("ios-native/Sources"))
    inputs.file(rootProject.layout.projectDirectory.file("ios-native/build-dist.sh"))
    outputs.dir(iosNativeDistDir)
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
                }
            }
        }
        binaries.framework {
            baseName = "EncatchKmpTester"
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
            baseName = "EncatchKmpTester"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kmp-sdk"))
            implementation(libs.kotlinx.serialization.json)
        }
        val androidMain by getting {
            dependencies {
                // :android (not :core directly) — needed for the raw EncatchInlineFormView type
                // this app embeds directly; business logic goes through :kmp-sdk.
                implementation(project(":android"))
                implementation(libs.androidx.core.ktx)
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

tasks.matching { it.name.startsWith("cinteropEncatchBridge") }.configureEach {
    dependsOn(buildIosNativeDist)
}

android {
    namespace = "com.encatch.kmptester"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.encatch.kmptester"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
