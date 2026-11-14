// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion domain/usecases/notifications/NotificationPreferencesUseCases.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.usecases.notifications

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.NotificationPreferencesRepository
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** The preferences; [AppException.Unsupported] from a server without them (Settings offers no screen then). */
class LoadNotificationPreferencesUseCase(
    private val repository: NotificationPreferencesRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String): NotificationPreferences = guard(accountId) {
        if (!features.has(accountId, Features.NOTIFICATIONS)) throw AppException.Unsupported(Features.NOTIFICATIONS)
        repository.preferences(accountId)
    }
}

/** Saves the preferences; the answer is what the server now holds. */
class SaveNotificationPreferencesUseCase(private val repository: NotificationPreferencesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, preferences: NotificationPreferences): NotificationPreferences =
        guard(accountId) { repository.save(accountId, preferences) }
}
