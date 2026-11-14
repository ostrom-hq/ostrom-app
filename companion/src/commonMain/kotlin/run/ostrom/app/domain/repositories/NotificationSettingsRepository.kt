// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/NotificationPreferencesRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationSettings
import run.ostrom.app.domain.models.PushTestOutcome

/**
 * The member's notification switches on an account's server, per channel and kind (`/api/v1/notifications/settings`),
 * and the test push. Failures are [run.ostrom.app.domain.models.AppException]s.
 */
interface NotificationSettingsRepository {
    suspend fun settings(accountId: String): NotificationSettings

    /** Saves one switch and answers all of them, as the server now holds them. */
    suspend fun set(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean): NotificationSettings

    /** A test notification to the member's own phones, at once. */
    suspend fun test(accountId: String): PushTestOutcome
}
