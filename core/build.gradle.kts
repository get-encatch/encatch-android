plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    signing
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
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

publishing {
    repositories {
        maven {
            name = "sonatype"
            url = uri(
                if (version.toString().endsWith("-beta") || version.toString().contains("-beta"))
                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                else
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/",
            )
            credentials {
                username = providers.gradleProperty("sonatypeUsername").orNull ?: System.getenv("SONATYPE_USERNAME")
                password = providers.gradleProperty("sonatypePassword").orNull ?: System.getenv("SONATYPE_PASSWORD")
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Encatch Core")
            description.set("Platform-agnostic business logic for the Encatch Android SDK (networking, storage, session management).")
            url.set("https://github.com/encatch/encatch-android")
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
                url.set("https://github.com/encatch/encatch-android")
                connection.set("scm:git:https://github.com/encatch/encatch-android.git")
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey").orNull ?: System.getenv("SIGNING_KEY")
    val signingPassword = providers.gradleProperty("signingPassword").orNull ?: System.getenv("SIGNING_PASSWORD")
    isRequired = !version.toString().endsWith("-SNAPSHOT")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
