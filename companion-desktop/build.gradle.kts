// SPDX-License-Identifier: Apache-2.0
// Laid out like TeamCode's companion-desktop/build.gradle.kts (Apache-2.0); see NOTICE.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The desktop app (macOS, Windows, Linux): the shared app of :companion in a window.
kotlin { jvmToolchain(21) }

dependencies {
    implementation(projects.companion)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.coroutines.swing)
}

// Installers take MAJOR.MINOR.PATCH only, and macOS refuses a major version of 0: packaging a 0.x fails instead of
// publishing another number than the app's.
val desktopPackageVersion = project.version.toString().substringBefore('-')
// The runnable jar (packageUberJarForCurrentOS, what CI builds on main) has no such limit and is not guarded.
val installerTasks = Regex("""(package|packageRelease)(Dmg|Msi|Exe|Deb|Rpm|Pkg|DistributionForCurrentOS)|create(Release)?Distributable""")
tasks.matching { installerTasks.matches(it.name) }.configureEach {
    val packaged = desktopPackageVersion
    doFirst {
        check(!packaged.startsWith("0.")) { "Ostrom Desktop installers refuse a major version of 0: package version 1.0.0 or later (got $packaged)." }
    }
}

compose.desktop {
    application {
        mainClass = "run.ostrom.app.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Ostrom"
            packageVersion = desktopPackageVersion
            macOS { bundleID = "run.ostrom.app.desktop" }
            windows {
                perUserInstall = true
                // Never changes, so a new version replaces the previous one.
                upgradeUuid = "6f1d8a52-3b7e-4c1a-9d2e-5a0b7c4e8f13"
                menu = true
                shortcut = true
            }
            // Preferences, the Java HTTP engine and the keyring's native access; jdk.crypto.ec for TLS to ECDSA servers.
            modules("java.net.http", "java.prefs", "jdk.crypto.ec", "jdk.unsupported")
        }
    }
}
