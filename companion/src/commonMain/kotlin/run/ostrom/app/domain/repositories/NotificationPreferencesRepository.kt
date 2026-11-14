// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion domain/repositories/NotificationPreferencesRepository.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.NotificationPreferences

/** The person's notification preferences on an account's server. Failures are [run.ostrom.app.domain.models.AppException]s. */
interface NotificationPreferencesRepository {
    suspend fun preferences(accountId: String): NotificationPreferences

    /** Replaces them all and answers them as the server now holds them. */
    suspend fun save(accountId: String, preferences: NotificationPreferences): NotificationPreferences
}
