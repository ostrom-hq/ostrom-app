// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion infrastructure/repositories/RemoteNotificationPreferencesRepository.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.QuietHours
import run.ostrom.app.domain.repositories.NotificationPreferencesRepository
import run.ostrom.core.api.models.NotificationPreferences as ApiPreferences
import run.ostrom.core.api.models.QuietHours as ApiQuietHours

/** The preferences over the typed client (`GET` and `PUT /api/v1/me/notifications`). */
class RemoteNotificationPreferencesRepository(private val calls: AccountCalls) : NotificationPreferencesRepository {
    override suspend fun preferences(accountId: String): NotificationPreferences =
        calls.withAccount(accountId) { client -> client.notifications.preferences().domain() }

    override suspend fun save(accountId: String, preferences: NotificationPreferences): NotificationPreferences =
        calls.withAccount(accountId) { client ->
            client.notifications.save(
                ApiPreferences(
                    preferences.requests, preferences.decisions, preferences.spending,
                    preferences.quietHours?.let { ApiQuietHours(it.start, it.end, it.timeZone) },
                ),
            ).domain()
        }

    private fun ApiPreferences.domain() =
        NotificationPreferences(requests, decisions, spending, quietHours?.let { QuietHours(it.start, it.end, it.timeZone) })
}
