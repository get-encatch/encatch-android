plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

// Android + desktop/JVM only. iOS moved to swift/ (pure Swift, no Kotlin/Native dependency).
// :core previously also targeted
// iosArm64/iosSimulatorArm64 to produce EncatchCore.xcframework for the (now-deleted) swift/
// package; nothing links that anymore.

kotlin {
    // Pinned below the Kotlin version we actually build with (2.3.21 at time of writing) so
    // consuming apps on an older Kotlin Gradle Plugin can still read this published library's
    // binary metadata. Kotlin's compiler only supports apiVersion/languageVersion up to 2 minor
    // versions behind the compiler itself, so 2.1 (released Nov 2024) is the oldest non-deprecated
    // target available from 2.3.x — chosen deliberately for broad compatibility with SDK
    // consumers, not because our own code needs anything older. Raise this in lockstep with the
    // project's Kotlin version over time, keeping roughly a 1-2 minor-version buffer behind it.
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.multiplatform.settings)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings)
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.startup)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

android {
    namespace = "com.encatch.core"
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
        name.set("Encatch Core")
        description.set("Platform-agnostic business logic for the Encatch Android SDK (networking, storage, session management).")
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
