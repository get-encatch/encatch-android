plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.encatch.sampleapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.encatch.sampleapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Overridden for automated screenshot testing: -PmockServerBaseUrl=http://10.0.2.2:8089
        // points the sample app at :mock-server instead of the real backend. Empty by default —
        // the manual "Init SDK" button keeps using EncatchConfig's real production defaults.
        buildConfigField(
            "String",
            "MOCK_SERVER_BASE_URL",
            "\"${project.findProperty("mockServerBaseUrl") ?: ""}\"",
        )
    }

    buildFeatures {
        buildConfig = true
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":android"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
