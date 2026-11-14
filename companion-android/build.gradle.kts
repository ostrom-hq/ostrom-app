// SPDX-License-Identifier: Apache-2.0
// Laid out like TeamCode's companion-android/build.gradle.kts (Apache-2.0); see NOTICE.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

dependencies {
    implementation(projects.companion)
    implementation(libs.androidx.activity.compose)
    // FragmentActivity, which BiometricPrompt shows over.
    implementation(libs.androidx.biometric)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

// versionCode from the app version: (major * 1 000 000 + minor * 1 000 + patch) * 100 + the store upload counter.
// fastlane passes the whole code as `ostromVersionCode` (docs/distribution.md); other builds use counter 0.
val semver = Regex("""(\d+)\.(\d+)\.(\d+).*""").matchEntire(project.version.toString())!!.groupValues.drop(1).map(String::toInt)
val ostromVersionCode = providers.gradleProperty("ostromVersionCode").map(String::toInt)
    .orElse(maxOf(1, (semver[0] * 1_000_000 + semver[1] * 1_000 + semver[2]) * 100)).get()
// Play's ceiling: above it the code would wrap around instead of failing.
// Each field has its own digits: a minor or patch above 999 would carry into the next field and collide.
check(semver[0] <= 20 && semver[1] <= 999 && semver[2] <= 999) { "Version ${project.version} does not fit versionCode (major up to 20, minor and patch up to 999)." }
check(ostromVersionCode in 1..2_100_000_000) { "versionCode $ostromVersionCode is outside 1..2100000000." }
// The upload key stays on the maintainer's machine (fastlane/keystore.jks, git-ignored) with its passwords in SIGNING_*.
val uploadKeystore = rootProject.file("fastlane/keystore.jks")

android {
    namespace = "run.ostrom.app.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "run.ostrom.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = ostromVersionCode
        versionName = project.version.toString()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
            // Bouncy Castle's OSGi manifest of its Java 9 classes: no use on Android, and a duplicate beside others.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    signingConfigs {
        // The key and its passwords together: a keystore alone leaves the release build unsigned.
        val signing = listOf("SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD").map { System.getenv(it) }
        if (uploadKeystore.exists() && signing.none { it.isNullOrEmpty() }) {
            create("release") {
                storeFile = uploadKeystore
                storePassword = signing[0]
                keyAlias = signing[1]
                keyPassword = signing[2]
            }
        }
    }
    buildTypes {
        // Without the upload key the release build is unsigned, and the fastlane lane refuses to run.
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
