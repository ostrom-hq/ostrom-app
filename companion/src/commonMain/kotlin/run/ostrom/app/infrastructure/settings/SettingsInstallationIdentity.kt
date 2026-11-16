// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/settings/SettingsInstallationIdentity.kt; see NOTICE.
package run.ostrom.app.infrastructure.settings

import com.russhwolf.settings.Settings
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.services.InstallationIdentity
import run.ostrom.core.api.models.DeviceLimits

/** The installation id in the preferences: drawn once, never removed by a sign-out (the phones'; the desktop shares the CLI's). */
class SettingsInstallationIdentity(private val settings: Settings, private val ids: IdGenerator) : InstallationIdentity {
    override suspend fun id(): String =
        settings.getStringOrNull(KEY)?.takeIf { INSTALLATION_ID.matches(it) } ?: ids.newId().also { settings.putString(KEY, it) }

    companion object {
        /** Also read by the desktop, which publishes an id it drew here into the CLI's folder. */
        const val KEY = "installation.id"
    }
}

/** An installation id, a lowercase UUID, as the contract and the CLI check it (also the desktop's, without ostrom-api). */
val INSTALLATION_ID: Regex = DeviceLimits.INSTALLATION_ID
