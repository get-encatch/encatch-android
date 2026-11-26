plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Declared at the root (apply false) so every publishing module shares one plugin classloader.
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "com.encatch"
    // Pre-1.0 like the Swift SDK (encatch-swift): 0.x signals the API can still move.
    version = "0.2.0-beta.4"
}

/**
 * Releases go to Encatch's Maven repository, https://packages.encatch.com/maven (an S3 bucket behind
 * CloudFront), not Maven Central. Versions 0.1.0 and 0.1.1 were published to Maven Central and are also
 * in Encatch's repository.
 *
 * Every publishing module (core, android, kmp-sdk, compose-sdk) gets the `encatchPackages` repository.
 * Its task, `publishAllPublicationsToEncatchPackagesRepository`, writes to a local folder by default
 * (build/encatch-packages), so running it by mistake uploads nothing. A release passes
 * `-PencatchPackagesUrl=s3://encatch-packages.s3.ap-south-1.amazonaws.com/maven` and runs from a
 * maintainer's Mac through Encatch's release script, which also refuses a version already published,
 * clears CloudFront's cached version lists and lists the release on packages.encatch.com. Bump the
 * version below first: a published version never changes.
 *
 * The upload uses the AWS credentials Gradle's S3 support finds (environment variables, or a profile
 * with static keys or role_arn in ~/.aws/credentials, named by AWS_PROFILE); an SSO profile isn't read,
 * so export its credentials first (`eval "$(aws configure export-credentials --profile <p> --format env)"`).
 * Every file is signed as before (`signAllPublications()` in each module). Releasing builds the iOS
 * klibs, so it needs a Mac with Xcode; it regenerates swift/dist (restore it before committing).
 */
val encatchPackagesUrl: String = providers.gradleProperty("encatchPackagesUrl")
    .getOrElse(layout.buildDirectory.dir("encatch-packages").get().asFile.toURI().toString())

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "encatchPackages"
                    url = uri(encatchPackagesUrl)
                    if (encatchPackagesUrl.startsWith("s3://")) {
                        authentication { create<AwsImAuthentication>("awsIm") }
                    }
                }
            }
        }
        // The repository's tasks, told apart by name (a task's repository isn't set while tasks are
        // configured); plain values only, so the checks also work with the configuration cache.
        tasks.withType<PublishToMavenRepository>().configureEach {
            if (name.endsWith("ToEncatchPackagesRepository")) {
                val version = project.version.toString()
                val artifact = project.name
                val uploading = encatchPackagesUrl.startsWith("s3://")
                doFirst {
                    check(!version.endsWith("-SNAPSHOT")) { "$version is a snapshot: set a release version before publishing" }
                    if (uploading) {
                        // A published version never changes: refuse one that's already there.
                        val pom = java.net.URI("https://packages.encatch.com/maven/com/encatch/$artifact/$version/$artifact-$version.pom").toURL()
                        val status = (pom.openConnection() as java.net.HttpURLConnection).run {
                            requestMethod = "HEAD"
                            connectTimeout = 15_000
                            readTimeout = 15_000
                            try { responseCode } finally { disconnect() }
                        }
                        check(status == 404) {
                            if (status == 200) "com.encatch:$artifact:$version is already published; bump the version"
                            else "couldn't check whether com.encatch:$artifact:$version is published (HTTP $status)"
                        }
                    }
                }
            }
        }
    }
}
