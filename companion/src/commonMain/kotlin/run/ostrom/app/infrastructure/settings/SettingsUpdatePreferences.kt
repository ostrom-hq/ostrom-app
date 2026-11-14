// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/settings/SettingsUpdatePreferences.kt; see NOTICE.
package run.ostrom.app.infrastructure.settings

import com.russhwolf.settings.Settings
import run.ostrom.app.domain.repositories.UpdatePreferences

/** [UpdatePreferences] in the app's preferences. */
class SettingsUpdatePreferences(private val settings: Settings) : UpdatePreferences {
    override var dismissedVersion: String?
        get() = settings.getStringOrNull(DISMISSED_KEY)
        set(value) = if (value == null) settings.remove(DISMISSED_KEY) else settings.putString(DISMISSED_KEY, value)
    override var lastAttemptMillis: Long?
        get() = settings.getLongOrNull(ATTEMPT_KEY)
        set(value) = if (value == null) settings.remove(ATTEMPT_KEY) else settings.putLong(ATTEMPT_KEY, value)
    override var latestVersion: String?
        get() = settings.getStringOrNull(LATEST_KEY)
        set(value) = if (value == null) settings.remove(LATEST_KEY) else settings.putString(LATEST_KEY, value)

    private companion object {
        const val DISMISSED_KEY = "updates.cli.dismissedVersion"
        const val ATTEMPT_KEY = "updates.cli.lastAttempt"
        const val LATEST_KEY = "updates.cli.latestVersion"
    }
}
