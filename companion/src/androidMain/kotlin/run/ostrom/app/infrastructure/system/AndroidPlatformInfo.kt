// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/androidMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/AndroidPlatformInfo.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import android.os.Build
import run.ostrom.app.BuildInfo
import run.ostrom.app.domain.services.PlatformInfo

object AndroidPlatformInfo : PlatformInfo {
    override val platform = "Android"
    override val deviceModel: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
    override val appVersion: String = BuildInfo.VERSION
}
