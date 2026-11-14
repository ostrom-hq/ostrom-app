// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/notifications/NotificationPreferencesUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.notifications

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationSettings
import run.ostrom.app.domain.models.NotificationSettingsView
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.NotificationSettingsRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

/**
 * The switches, and whether the member may send a test push (a server that announces `notifications.push.test.v1`);
 * [AppException.Unsupported] from a server without `notifications.v1` (Settings offers no screen then). The features
 * are read again: the test may have been turned on since.
 */
class LoadNotificationSettingsUseCase(
    private val repository: NotificationSettingsRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String): NotificationSettingsView = guard(accountId) {
        val offered = features.capabilities(accountId, refresh = true).features
        if (Features.NOTIFICATIONS !in offered) throw AppException.Unsupported(Features.NOTIFICATIONS)
        NotificationSettingsView(repository.settings(accountId), canTest = Features.NOTIFICATIONS_PUSH_TEST in offered)
    }
}

/** Turns one kind on or off on one channel for the member; the answer is every switch as the server holds them. */
class SetNotificationSettingUseCase(private val repository: NotificationSettingsRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean): NotificationSettings =
        guard(accountId) { repository.set(accountId, channel, kind, enabled) }
}

/** Sends a test notification to the member's own phones, at once. */
class SendTestPushUseCase(private val repository: NotificationSettingsRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): PushTestOutcome = guard(accountId) { repository.test(accountId) }
}
