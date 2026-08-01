plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

android {
    namespace = "com.encatch.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    api(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "android"

                pom {
                    name.set("Encatch Android")
                    description.set("Encatch Android SDK — collect user feedback via native/WebView forms in Android apps.")
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

        repositories {
            maven {
                name = "sonatype"
                url = uri(
                    if (version.toString().contains("-beta"))
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
    }

    signing {
        val signingKey = providers.gradleProperty("signingKey").orNull ?: System.getenv("SIGNING_KEY")
        val signingPassword = providers.gradleProperty("signingPassword").orNull ?: System.getenv("SIGNING_PASSWORD")
        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }
}
