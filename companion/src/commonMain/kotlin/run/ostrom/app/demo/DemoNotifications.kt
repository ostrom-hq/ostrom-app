// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import kotlinx.coroutines.flow.update
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.NotificationSettings
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.repositories.NotificationPreferencesRepository
import run.ostrom.app.domain.repositories.NotificationSettingsRepository

/** The demo's preferences, in the demo's memory until it ends: every kind on, no quiet hours. */
class DemoNotificationPreferencesRepository(private val demo: DemoMode) : NotificationPreferencesRepository {
    override suspend fun preferences(accountId: String): NotificationPreferences = demo.store().notifications.value

    override suspend fun save(accountId: String, preferences: NotificationPreferences): NotificationPreferences {
        demo.store().notifications.update { preferences }
        return preferences
    }
}

/** The demo account's preferences from memory, every other account's from its server. */
class DemoAwareNotificationPreferencesRepository(
    private val real: NotificationPreferencesRepository,
    private val demo: NotificationPreferencesRepository,
) : NotificationPreferencesRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun preferences(accountId: String) = of(accountId).preferences(accountId)
    override suspend fun save(accountId: String, preferences: NotificationPreferences) = of(accountId).save(accountId, preferences)
}

/** The demo's switches, in the demo's memory until it ends. The demo has no phone to test: its server offers no test. */
class DemoNotificationSettingsRepository(private val demo: DemoMode) : NotificationSettingsRepository {
    override suspend fun settings(accountId: String): NotificationSettings = demo.store().notificationSettings.value

    override suspend fun set(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean): NotificationSettings {
        demo.store().notificationSettings.update { it.with(channel, kind, enabled) }
        return demo.store().notificationSettings.value
    }

    override suspend fun test(accountId: String): PushTestOutcome = PushTestOutcome.NoPhone
}

/** The demo account's switches from memory, every other account's from its server. */
class DemoAwareNotificationSettingsRepository(
    private val real: NotificationSettingsRepository,
    private val demo: NotificationSettingsRepository,
) : NotificationSettingsRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun settings(accountId: String) = of(accountId).settings(accountId)
    override suspend fun set(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean) =
        of(accountId).set(accountId, channel, kind, enabled)
    override suspend fun test(accountId: String) = of(accountId).test(accountId)
}
