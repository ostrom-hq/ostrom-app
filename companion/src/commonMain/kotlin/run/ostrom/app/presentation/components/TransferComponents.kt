// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/components/TransferComponents.kt; see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsLeftRight
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.usecases.transfers.TransferAvailabilityUseCase
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.TransferError
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.transfer_menu
import run.ostrom.app.resources.transfer_problem_effort
import run.ostrom.app.resources.transfer_problem_limit
import run.ostrom.app.resources.transfer_problem_model
import run.ostrom.app.resources.transfer_problem_not_found
import run.ostrom.app.resources.transfer_problem_not_running
import run.ostrom.app.resources.transfer_problem_owner_changed
import run.ostrom.app.resources.transfer_problem_permission
import run.ostrom.app.resources.transfer_problem_profile
import run.ostrom.app.resources.transfer_problem_quota
import run.ostrom.app.resources.transfer_problem_timeout
import run.ostrom.app.resources.transfer_problem_unavailable
import run.ostrom.app.resources.transfer_refused_other
import run.ostrom.app.resources.transfers_title

/** The sentence for a refusal of the server, by its error key; an unknown key is named as is. */
@Composable
fun transferErrorText(error: TransferError): String = when (error) {
    is TransferError.Text -> error.text.words()
    is TransferError.Refused -> refusal(error.code)?.let { stringResource(it) } ?: stringResource(Res.string.transfer_refused_other, error.code)
}

/** Why a transfer failed or was cancelled (its failure code), in a few words; an unknown code is named as is. */
@Composable
fun transferProblem(code: String): String = when (code) {
    "recipient_not_found" -> stringResource(Res.string.transfer_problem_not_found)
    "recipient_unavailable" -> stringResource(Res.string.transfer_problem_unavailable)
    "profile_unassigned" -> stringResource(Res.string.transfer_problem_profile)
    "cloud_permission_denied" -> stringResource(Res.string.transfer_problem_permission)
    "model_not_allowed" -> stringResource(Res.string.transfer_problem_model)
    "effort_not_allowed" -> stringResource(Res.string.transfer_problem_effort)
    "recipient_no_quota" -> stringResource(Res.string.transfer_problem_quota)
    "cloud_session_limit" -> stringResource(Res.string.transfer_problem_limit)
    "transfer_timeout" -> stringResource(Res.string.transfer_problem_timeout)
    "session_not_running" -> stringResource(Res.string.transfer_problem_not_running)
    "owner_changed" -> stringResource(Res.string.transfer_problem_owner_changed)
    else -> code
}

/** Waiting offers in amber, a change of owner under way with the accent, failures red, the rest muted. */
@Composable
fun TransferStateChip(state: TransferState, modifier: Modifier = Modifier) {
    val tone = when (state) {
        TransferState.OFFERED -> Tone.WARN
        TransferState.ACCEPTED -> Tone.ACCENT
        TransferState.COMPLETED -> Tone.NEUTRAL
        TransferState.FAILED -> Tone.DANGER
        else -> Tone.MUTED
    }
    Chip(stringResource(state.label()), modifier, tone = tone, dot = state == TransferState.ACCEPTED)
}

/** A setting row of a [Group]: a title, a line under it, and a switch; the whole row toggles. */
@Composable
fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Ostrom.colors
    GroupRow(modifier.heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent, checkedBorderColor = colors.accent,
                uncheckedTrackColor = colors.panelAlt, uncheckedThumbColor = colors.muted, uncheckedBorderColor = colors.line,
            ),
        )
    }
}

/**
 * "Transfer…" on a cloud session's record, shown only when the member may give this session away (its owner, a cloud
 * session that is neither being prepared, ended nor already changing owner) on a server with transfers.
 */
@Composable
fun TransferButton(accountId: String, session: CloudSession, onClick: () -> Unit) {
    val availability: TransferAvailabilityUseCase = koinInject()
    val visible by produceState(false, accountId, session.id, session.status, session.ownerId, session.remote) { value = availability.canTransfer(accountId, session) }
    if (!visible) return
    AppButton(stringResource(Res.string.transfer_menu), onClick = onClick, kind = ButtonKind.SECONDARY, small = true, fill = false, icon = PhosphorIcons.Regular.ArrowsLeftRight,
        modifier = Modifier.testTag("transfer"))
}

/** The cloud sessions list's way to the transfers list, on a server with transfers. */
@Composable
fun TransfersButton(accountId: String, onClick: () -> Unit) {
    val availability: TransferAvailabilityUseCase = koinInject()
    val visible by produceState(false, accountId) { value = availability.available(accountId) }
    if (visible) AppIconButton(PhosphorIcons.Regular.ArrowsLeftRight, stringResource(Res.string.transfers_title), onClick = onClick, kind = IconButtonKind.OUTLINED, modifier = Modifier.testTag("open-transfers"))
}
