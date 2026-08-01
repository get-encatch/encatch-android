import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// Variant 5: a sample app that is itself a Kotlin Multiplatform project (shared commonMain
// business logic calling :core's Encatch API directly), rather than a single-platform app that
// merely depends on KMP :core (variants 1-4). Validates the real Gradle KMP consumption path on
// Android and the XCFramework consumption path on iOS. UI is intentionally minimal (status text +
// buttons, no hosted-form rendering) — the form-rendering UI is already proven by variants 1-4;
// this variant's job is the dependency/build-path shape, not additional UI coverage.
val xcf = XCFramework("EncatchKmpSample")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    iosArm64 {
        binaries.framework {
            baseName = "EncatchKmpSample"
            isStatic = true
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "EncatchKmpSample"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
        }
        val androidMain by getting {
            dependencies {
                implementation(project(":android"))
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.appcompat)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val iosMain by creating {
            dependsOn(commonMain.get())
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
