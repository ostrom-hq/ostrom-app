// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/SessionComponents.kt; see NOTICE.
package run.ostrom.app.presentation.components

import run.ostrom.app.domain.models.SessionHost

/** The system a device reports, worded as its vendor does; null until the device reported it. */
fun systemOf(host: SessionHost): String? = systemOf(host.platform, host.osVersion)

fun systemOf(platform: String?, osVersion: String?): String? {
    val name = when (platform) {
        null -> return null
        "macos" -> "macOS"
        "windows" -> "Windows"
        "linux" -> "Linux"
        "ios" -> "iOS"
        "android" -> "Android"
        else -> platform
    }
    return listOfNotNull(name, osVersion?.takeIf { it.isNotBlank() }).joinToString(" ")
}
