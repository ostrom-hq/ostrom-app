// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/DesktopPlatformInfo.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import run.ostrom.app.BuildInfo
import run.ostrom.app.domain.services.PlatformInfo

object DesktopPlatformInfo : PlatformInfo {
    override val platform: String = platformOf(System.getProperty("os.name"))
    override val deviceModel: String by lazy { deviceModelOf(platform, System::getenv) }
    override val appVersion: String = BuildInfo.VERSION
}

/** `macOS`, `Windows` or `Linux`, from the JVM's `os.name`. */
internal fun platformOf(osName: String?): String {
    val name = osName.orEmpty()
    return when {
        name.startsWith("Mac", ignoreCase = true) -> "macOS"
        name.startsWith("Windows", ignoreCase = true) -> "Windows"
        else -> "Linux"
    }
}

/**
 * The host name when the environment gives it, as a member recognizes the machine. No resolver lookup: it can block for
 * seconds and this is read when the login screen opens. The member can edit the name there.
 */
internal fun deviceModelOf(platform: String, env: (String) -> String?): String =
    env("COMPUTERNAME") ?: env("HOSTNAME") ?: "$platform desktop"
