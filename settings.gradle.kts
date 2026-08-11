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
include(":examples:sample-app")
include(":mock-server")
include(":examples:compose-sample")
include(":examples:kmp-sample")
include(":kmp-sdk")
include(":compose-sdk")
include(":integrations:encatch-android-tester")
include(":integrations:encatch-kmp-tester")
include(":integrations:encatch-compose-tester")
