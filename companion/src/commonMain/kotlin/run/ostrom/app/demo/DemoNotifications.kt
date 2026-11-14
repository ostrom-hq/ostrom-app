// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import kotlinx.coroutines.flow.update
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.repositories.NotificationPreferencesRepository

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
