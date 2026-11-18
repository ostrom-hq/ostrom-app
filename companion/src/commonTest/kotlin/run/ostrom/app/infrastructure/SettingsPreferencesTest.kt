// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import com.russhwolf.settings.MapSettings
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.ThemePreference
import run.ostrom.app.infrastructure.settings.SettingsPreferencesRepository
import run.ostrom.app.infrastructure.settings.SettingsUpdatePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The theme, the permission mode and the update banner's memory, as the preferences keep them. */
class SettingsPreferencesTest {
    private val settings = MapSettings()

    @Test fun theDefaultsAreTheSystemThemeAndAskingForPermission() {
        val preferences = SettingsPreferencesRepository(settings)
        assertEquals(ThemePreference.SYSTEM, preferences.theme.value)
        assertEquals(PermissionMode.ASK, preferences.permissionMode)
    }

    @Test fun aChosenThemeIsPublishedAndRememberedAcrossARestart() {
        val preferences = SettingsPreferencesRepository(settings)
        preferences.setTheme(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, preferences.theme.value)
        assertEquals(ThemePreference.DARK, SettingsPreferencesRepository(settings).theme.value)
    }

    @Test fun aStoredThemeThatNoLongerExistsFallsBackToTheSystemTheme() {
        settings.putString("preferences.theme", "SEPIA")
        assertEquals(ThemePreference.SYSTEM, SettingsPreferencesRepository(settings).theme.value)
    }

    @Test fun thePermissionModeIsStoredByItsWireName() {
        val preferences = SettingsPreferencesRepository(settings)
        preferences.setPermissionMode(PermissionMode.BYPASS)
        assertEquals(PermissionMode.BYPASS.wire, settings.getStringOrNull("preferences.permissionMode"))
        assertEquals(PermissionMode.BYPASS, preferences.permissionMode)
    }

    @Test fun anUnknownStoredPermissionModeAsksAgain() {
        settings.putString("preferences.permissionMode", "yolo")
        assertEquals(PermissionMode.ASK, SettingsPreferencesRepository(settings).permissionMode)
    }

    @Test fun theUpdateBannerRemembersWhatItWasGivenAndForgetsWhenSetToNull() {
        val updates = SettingsUpdatePreferences(settings)
        assertNull(updates.dismissedVersion)
        assertNull(updates.lastAttemptMillis)
        assertNull(updates.latestVersion)
        updates.dismissedVersion = "1.2.0"
        updates.lastAttemptMillis = 42L
        updates.latestVersion = "1.3.0"
        val restarted = SettingsUpdatePreferences(settings)
        assertEquals("1.2.0", restarted.dismissedVersion)
        assertEquals(42L, restarted.lastAttemptMillis)
        assertEquals("1.3.0", restarted.latestVersion)
        restarted.dismissedVersion = null
        restarted.lastAttemptMillis = null
        restarted.latestVersion = null
        assertNull(settings.getStringOrNull("updates.cli.dismissedVersion"))
        assertNull(settings.getLongOrNull("updates.cli.lastAttempt"))
        assertNull(settings.getStringOrNull("updates.cli.latestVersion"))
    }
}
