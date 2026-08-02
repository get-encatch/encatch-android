plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Shared Kotlin/Native (iOS-only) UI source — used by :compose-sample and :kmp-sample's iosMain
// as an ordinary Gradle source dependency, NOT a separately-shipped XCFramework. Each consumer
// compiles this source into its own binary, same as :core — sharing SOURCE this way (rather than
// a third compiled framework) avoids reintroducing the duplicate-:core-singleton problem
// documented on EncatchNativeFormHost/EncatchNativeInlineFormView: an iOS app should still only
// ever link ONE Kotlin/Native framework that (transitively) embeds :core.
kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":core"))
            }
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
    }
}
