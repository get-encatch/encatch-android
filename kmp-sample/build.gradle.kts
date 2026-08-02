import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// Variant 5: a sample app that is itself a Kotlin Multiplatform project (shared commonMain
// business logic calling the platform SDK directly), rather than a single-platform app that
// merely depends on KMP :core (variants 1-4). Validates the real Gradle KMP consumption path on
// Android (:core) and — since this branch — the Kotlin/Native cinterop consumption path on iOS
// against ios-native/'s pure-Swift SDK (no :core/:ios-native-form-ui on iOS anymore). See
// /Users/godwin/.claude/plans/stateless-floating-ripple.md and compose-sample/build.gradle.kts's
// twin comment for the full rationale and how ios-native/dist/ was produced.
val xcf = XCFramework("EncatchKmpSample")

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
        val androidMain by getting {
            dependencies {
                implementation(project(":core"))
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
