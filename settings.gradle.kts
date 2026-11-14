// SPDX-License-Identifier: Apache-2.0
// The companion app: the shared Compose Multiplatform app (:companion) and its Android and desktop shells; the iOS
// shell is the Xcode project companion-ios/. Laid out like TeamCode's companion (see NOTICE).
rootProject.name = "ostrom-app"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // Google's repository serves only the Android plugin, AndroidX and Google's Android groups.
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google.android")
                includeGroupAndSubgroups("com.google.testing.platform")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Firebase too, for the Android app's push (FCM).
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google.android")
                includeGroupAndSubgroups("com.google.testing.platform")
                includeGroupAndSubgroups("com.google.firebase")
            }
        }
        mavenCentral()
        // ostrom-core (run.ostrom:ostrom-api, ostrom-client, ostrom-receipts) is published on GitHub Packages: gpr.user / gpr.key in
        // ~/.gradle/gradle.properties. A checkout next to this one is used instead when there is one (below).
        maven("https://maven.pkg.github.com/ostrom-hq/ostrom-core") {
            name = "ostromCore"
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("run.ostrom") }
        }
    }
}

// ostrom-core from a checkout instead of GitHub Packages: CI checks it out next to this repository at the commit of
// gradle/ostrom-core.ref, and a contributor with both clones side by side gets the same. -Postrom.core.local=false
// forces the published artifacts; -Postrom.core.path=DIR names another checkout.
val corePath = providers.gradleProperty("ostrom.core.path").orElse("../ostrom-core").get()
val coreLocal = providers.gradleProperty("ostrom.core.local").orNull?.toBooleanStrict() ?: file(corePath).resolve("settings.gradle.kts").exists()
if (coreLocal) {
    includeBuild(corePath) {
        dependencySubstitution {
            for (module in listOf("api", "client", "receipts")) {
                substitute(module("run.ostrom:ostrom-$module")).using(project(":$module"))
            }
        }
    }
}

include(":companion", ":companion-android", ":companion-desktop")
