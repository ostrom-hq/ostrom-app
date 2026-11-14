// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteNotificationPreferencesRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationSettings
import run.ostrom.app.domain.models.NotificationSwitch
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.models.PushTestPhone
import run.ostrom.app.domain.models.PushTestStatus
import run.ostrom.app.domain.repositories.NotificationSettingsRepository
import run.ostrom.core.client.OstromException
import run.ostrom.core.api.models.NotificationSettings as ApiSettings

/**
 * The switches (`GET` and `PUT /api/v1/notifications/settings`) and the test push (`POST
 * /api/v1/notifications/push/test`) over the typed client, one request each. A kind this app does not know is left out.
 */
class RemoteNotificationSettingsRepository(private val calls: AccountCalls) : NotificationSettingsRepository {
    override suspend fun settings(accountId: String): NotificationSettings =
        calls.withAccount(accountId) { client -> client.notifications.settings().domain() }

    override suspend fun set(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean): NotificationSettings =
        calls.withAccount(accountId) { client -> client.notifications.setSetting(channel, kind.wire, enabled).domain() }

    override suspend fun test(accountId: String): PushTestOutcome = calls.withAccount(accountId) { client ->
        try {
            val answer = client.notifications.sendTestPush()
            PushTestOutcome.Sent(answer.results.map { PushTestPhone(PushTestStatus.parse(it.status), it.error) })
        } catch (error: OstromException) {
            when (error.type.takeIf { error.status == CONFLICT }) {
                PUSH_OFF -> PushTestOutcome.Off
                PUSH_NO_DEVICE -> PushTestOutcome.NoPhone
                // A 429 push_test_rate_limited reaches the screen as AppException.RateLimited, with its Retry-After.
                else -> throw error
            }
        }
    }

    private fun ApiSettings.domain() = NotificationSettings(
        channels = channels,
        switches = settings.mapNotNull { setting ->
            NotificationKind.parse(setting.kind)?.let { NotificationSwitch(setting.channel, it) to setting.enabled }
        }.toMap(),
    )

    private companion object {
        const val CONFLICT = 409
        const val PUSH_OFF = "push_off"
        const val PUSH_NO_DEVICE = "push_no_device"
    }
}
