pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "encatch-android"

include(":core")
include(":android")
include(":sample-app")
include(":mock-server")
include(":compose-sample")
include(":kmp-sample")
include(":ios-native-form-ui")
