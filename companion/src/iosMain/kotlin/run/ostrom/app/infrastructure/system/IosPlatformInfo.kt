// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/iosMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/IosPlatformInfo.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import run.ostrom.app.BuildInfo
import run.ostrom.app.domain.services.PlatformInfo
import platform.UIKit.UIDevice

object IosPlatformInfo : PlatformInfo {
    override val platform = "iOS"
    /** `iPhone` or `iPad`: iOS does not give apps the name the owner chose. */
    override val deviceModel: String get() = UIDevice.currentDevice.model
    override val appVersion: String = BuildInfo.VERSION
}
