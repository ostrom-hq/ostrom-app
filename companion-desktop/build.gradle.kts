// SPDX-License-Identifier: Apache-2.0
// Laid out like TeamCode's companion-desktop/build.gradle.kts (Apache-2.0); see NOTICE.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The desktop app (macOS, Windows, Linux): the shared app of :companion in a window, plus what only makes sense on a
// computer: the menu bar icon, the `ostrom` CLI's account, the CLI's update banner.
kotlin { jvmToolchain(21) }

// The macOS keyring's native library (osxkeychain.so) sits inside the jkeychain jar, where jpackage does not sign it and
// notarization rejects the app. On a Mac that signs, the app takes a copy of that jar whose native library is signed with
// the Developer ID (hardened runtime, secure timestamp) instead of the original one. Ported from TeamCode.
val macosSigningIdentity = providers.environmentVariable("OSTROM_MACOS_SIGNING_IDENTITY").orNull
val macosSigningKeychain = providers.environmentVariable("OSTROM_MACOS_SIGNING_KEYCHAIN").orNull
val jkeychainOriginal by configurations.creating { isTransitive = false }
dependencies { jkeychainOriginal(libs.jkeychain) }
val jkeychainSignedDirectory = layout.buildDirectory.dir("signed-jkeychain/content")
val signJkeychainNatives by tasks.registering(Sync::class) {
    onlyIf { macosSigningIdentity != null }
    from(jkeychainOriginal.elements.map { files -> files.map { zipTree(it.asFile) } })
    into(jkeychainSignedDirectory)
    doLast {
        val identity = checkNotNull(macosSigningIdentity)
        destinationDir.walkTopDown().filter { it.isFile && it.extension in setOf("so", "dylib", "jnilib") }.forEach { library ->
            val command = mutableListOf("codesign", "--force", "--timestamp", "--options", "runtime", "--sign", "Developer ID Application: $identity")
            macosSigningKeychain?.let { command += listOf("--keychain", it) }
            command += library.absolutePath
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "codesign failed on ${library.name}: $output" }
        }
    }
}
val signedJkeychainJar by tasks.registering(Jar::class) {
    onlyIf { macosSigningIdentity != null }
    archiveFileName.set("jkeychain-signed.jar")
    destinationDirectory.set(layout.buildDirectory.dir("signed-jkeychain"))
    from(signJkeychainNatives.map { jkeychainSignedDirectory })
}
if (macosSigningIdentity != null) {
    configurations.runtimeClasspath { exclude(group = "pt.davidafsilva.apple", module = "jkeychain") }
}

dependencies {
    if (macosSigningIdentity != null) runtimeOnly(files(signedJkeychainJar))
    implementation(projects.companion)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.coroutines.swing)
    implementation(libs.multiplatform.settings)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.multiplatform.settings.test)
    testRuntimeOnly(libs.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

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
            // Signed with a Developer ID only on a Mac that provides one (docs/distribution.md): the release workflow
            // imports it into a temporary keychain and notarizes with notarytool. Windows installers are not signed (no
            // paid signing service): SmartScreen warns on first launch. Without the variables the build is unsigned.
            macOS {
                bundleID = "run.ostrom.app.desktop"
                // The phones' icon (companion-ios's AppIcon) in the macOS shape (icons/), as .ico and .png for the others.
                iconFile.set(project.file("icons/Ostrom.icns"))
                macosSigningIdentity?.let { name ->
                    signing {
                        sign.set(true)
                        identity.set(name)
                        macosSigningKeychain?.let { keychain.set(it) }
                    }
                }
                providers.environmentVariable("OSTROM_NOTARIZATION_APPLE_ID").orNull?.let { account ->
                    notarization {
                        appleID.set(account)
                        password.set(providers.environmentVariable("OSTROM_NOTARIZATION_PASSWORD"))
                        teamID.set(providers.environmentVariable("OSTROM_NOTARIZATION_TEAM_ID"))
                    }
                }
            }
            linux { iconFile.set(project.file("icons/Ostrom.png")) }
            windows {
                iconFile.set(project.file("icons/Ostrom.ico"))
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
