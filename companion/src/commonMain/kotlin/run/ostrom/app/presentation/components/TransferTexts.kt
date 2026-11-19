// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/TransferComponents.kt; see NOTICE.
package run.ostrom.app.presentation.components

import org.jetbrains.compose.resources.StringResource
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.transfer_refused_busy
import run.ostrom.app.resources.transfer_refused_conflict
import run.ostrom.app.resources.transfer_refused_disabled
import run.ostrom.app.resources.transfer_refused_expired
import run.ostrom.app.resources.transfer_refused_forbidden
import run.ostrom.app.resources.transfer_refused_invalid
import run.ostrom.app.resources.transfer_refused_not_open
import run.ostrom.app.resources.transfer_refused_not_running
import run.ostrom.app.resources.transfer_refused_pending
import run.ostrom.app.resources.transfer_refused_preparing
import run.ostrom.app.resources.transfer_refused_recipient
import run.ostrom.app.resources.transfer_refused_transferring
import run.ostrom.app.resources.transfer_state_accepted
import run.ostrom.app.resources.transfer_state_cancelled
import run.ostrom.app.resources.transfer_state_completed
import run.ostrom.app.resources.transfer_state_declined
import run.ostrom.app.resources.transfer_state_expired
import run.ostrom.app.resources.transfer_state_failed
import run.ostrom.app.resources.transfer_state_offered
import run.ostrom.app.resources.transfer_state_unknown

internal fun refusal(code: String): StringResource? = when (code) {
    "transfer_pending" -> Res.string.transfer_refused_pending
    "session_preparing" -> Res.string.transfer_refused_preparing
    "session_not_running" -> Res.string.transfer_refused_not_running
    "session_transferring", "transfer_in_progress" -> Res.string.transfer_refused_transferring
    "session_busy" -> Res.string.transfer_refused_busy
    "transfer_disabled" -> Res.string.transfer_refused_disabled
    "transfer_forbidden", "forbidden" -> Res.string.transfer_refused_forbidden
    "transfer_expired" -> Res.string.transfer_refused_expired
    "transfer_not_open" -> Res.string.transfer_refused_not_open
    "idempotency_conflict" -> Res.string.transfer_refused_conflict
    "invalid_transfer" -> Res.string.transfer_refused_invalid
    "recipient_ineligible" -> Res.string.transfer_refused_recipient
    else -> null
}

fun TransferState.label(): StringResource = when (this) {
    TransferState.OFFERED -> Res.string.transfer_state_offered
    TransferState.ACCEPTED -> Res.string.transfer_state_accepted
    TransferState.COMPLETED -> Res.string.transfer_state_completed
    TransferState.DECLINED -> Res.string.transfer_state_declined
    TransferState.CANCELLED -> Res.string.transfer_state_cancelled
    TransferState.EXPIRED -> Res.string.transfer_state_expired
    TransferState.FAILED -> Res.string.transfer_state_failed
    TransferState.UNKNOWN -> Res.string.transfer_state_unknown
}
