// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/DesktopPlatformInfo.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import run.ostrom.app.BuildInfo
import run.ostrom.app.domain.services.PlatformInfo

object DesktopPlatformInfo : PlatformInfo {
    override val platform: String = System.getProperty("os.name").orEmpty().let { name ->
        when {
            name.startsWith("Mac", ignoreCase = true) -> "macOS"
            name.startsWith("Windows", ignoreCase = true) -> "Windows"
            else -> "Linux"
        }
    }
    override val deviceModel: String by lazy {
        // The host name when the environment gives it, as a member recognizes the machine. No resolver lookup: it can
        // block for seconds and this is read when the login screen opens. The member can edit the name there.
        System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "$platform desktop"
    }
    override val appVersion: String = BuildInfo.VERSION
}
