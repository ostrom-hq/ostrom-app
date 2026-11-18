// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/screens/NotificationPreferencesScreen.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import org.jetbrains.compose.resources.StringResource
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationToggle
import run.ostrom.app.domain.models.PushTestStatus
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.notifications_decisions
import run.ostrom.app.resources.notifications_kind_session_failed
import run.ostrom.app.resources.notifications_kind_session_failed_hint
import run.ostrom.app.resources.notifications_kind_turn_completed
import run.ostrom.app.resources.notifications_kind_turn_completed_hint
import run.ostrom.app.resources.notifications_kind_turn_failed
import run.ostrom.app.resources.notifications_kind_turn_failed_hint
import run.ostrom.app.resources.notifications_kind_waiting
import run.ostrom.app.resources.notifications_kind_waiting_hint
import run.ostrom.app.resources.notifications_requests
import run.ostrom.app.resources.notifications_spending
import run.ostrom.app.resources.push_test_status_other
import run.ostrom.app.resources.push_test_status_rejected
import run.ostrom.app.resources.push_test_status_retry
import run.ostrom.app.resources.push_test_status_sent
import run.ostrom.app.resources.push_test_status_unregistered

internal fun PushTestStatus.label(): StringResource = when (this) {
    PushTestStatus.SENT -> Res.string.push_test_status_sent
    PushTestStatus.UNREGISTERED -> Res.string.push_test_status_unregistered
    PushTestStatus.RETRY -> Res.string.push_test_status_retry
    PushTestStatus.REJECTED -> Res.string.push_test_status_rejected
    PushTestStatus.OTHER -> Res.string.push_test_status_other
}

internal fun NotificationKind.title(): StringResource = when (this) {
    NotificationKind.WAITING -> Res.string.notifications_kind_waiting
    NotificationKind.TURN_COMPLETED -> Res.string.notifications_kind_turn_completed
    NotificationKind.TURN_FAILED -> Res.string.notifications_kind_turn_failed
    NotificationKind.SESSION_FAILED -> Res.string.notifications_kind_session_failed
}

internal fun NotificationKind.hint(): StringResource = when (this) {
    NotificationKind.WAITING -> Res.string.notifications_kind_waiting_hint
    NotificationKind.TURN_COMPLETED -> Res.string.notifications_kind_turn_completed_hint
    NotificationKind.TURN_FAILED -> Res.string.notifications_kind_turn_failed_hint
    NotificationKind.SESSION_FAILED -> Res.string.notifications_kind_session_failed_hint
}

internal fun NotificationToggle.label(): StringResource = when (this) {
    NotificationToggle.REQUESTS -> Res.string.notifications_requests
    NotificationToggle.DECISIONS -> Res.string.notifications_decisions
    NotificationToggle.SPENDING -> Res.string.notifications_spending
}
