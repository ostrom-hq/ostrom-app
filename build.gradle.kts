// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.jvm) apply false
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kover) apply false
}

val ostromAppVersion = providers.gradleProperty("ostromAppVersion").get()
require(Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?""").matches(ostromAppVersion)) {
    "ostromAppVersion must be a semantic version such as 1.0.0, got $ostromAppVersion."
}

allprojects {
    group = "run.ostrom"
    version = ostromAppVersion
    tasks.withType<Test>().configureEach { maxParallelForks = 2 }
}
