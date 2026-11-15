// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/TransferSheet.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppSheet
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.Avatar
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.SheetHeader
import run.ostrom.app.presentation.components.ToggleRow
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.transferErrorText
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.TransferViewModel
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_cancel
import run.ostrom.app.resources.action_done
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.transfer_administrator
import run.ostrom.app.resources.transfer_done_immediate
import run.ostrom.app.resources.transfer_done_offer
import run.ostrom.app.resources.transfer_intro
import run.ostrom.app.resources.transfer_no_member
import run.ostrom.app.resources.transfer_resume
import run.ostrom.app.resources.transfer_resume_body
import run.ostrom.app.resources.transfer_search
import run.ostrom.app.resources.transfer_submit
import run.ostrom.app.resources.transfer_title

/**
 * Transfer a session, in a sheet over it: pick a member (searched by login), optionally have the session resumed for
 * them once it is theirs, and send. The server decides what follows: an administrator's transfer is accepted at once, a
 * member's is an offer the recipient accepts, and the sheet says which.
 */
@Composable
fun TransferSheet(viewModel: TransferViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    AppSheet(onDismiss) {
        SheetHeader(
            stringResource(Res.string.transfer_title),
            centered = true,
            leading = { AppButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.GHOST, small = true, fill = false, modifier = Modifier.testTag("transfer-cancel")) },
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp).testTag("transfer-form"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val done = state.done
            if (done != null) {
                val name = state.recipient?.username ?: done.toMemberId
                Banner(stringResource(if (done.state == TransferState.OFFERED) Res.string.transfer_done_offer else Res.string.transfer_done_immediate, name), Tone.ACCENT, Modifier.testTag("transfer-done"))
                return@Column
            }
            Text(stringResource(Res.string.transfer_intro), color = colors.muted, fontSize = 14.sp, lineHeight = 20.sp)
            BusyRow(state.loading)
            if (state.members.isNotEmpty()) {
                AppTextField(state.query, viewModel::onQuery, label = stringResource(Res.string.transfer_search), tag = "transfer-search")
                val matches = state.matches
                if (matches.isEmpty()) Text(stringResource(Res.string.transfer_no_member), color = colors.muted, fontSize = 14.sp)
                else Group(Modifier.selectableGroup()) {
                    matches.forEachIndexed { index, member ->
                        if (index > 0) GroupDivider()
                        MemberOption(member, selected = state.recipientId == member.id) { viewModel.onRecipient(member.id) }
                    }
                }
                Group {
                    ToggleRow(stringResource(Res.string.transfer_resume), stringResource(Res.string.transfer_resume_body), state.resume, viewModel::onResume, Modifier.testTag("transfer-resume"))
                }
            } else if (!state.loading && state.error == null) {
                Text(stringResource(Res.string.transfer_no_member), color = colors.muted, fontSize = 14.sp)
            }
            state.error?.let { error ->
                Banner(transferErrorText(error), Tone.DANGER, Modifier.testTag("transfer-error"), action = if (state.members.isEmpty()) {
                    { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true, fill = false) }
                } else null)
            }
        }
        Box(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
            Box(Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)) {
                if (state.done != null) AppButton(stringResource(Res.string.action_done), onClick = onDismiss, modifier = Modifier.testTag("transfer-close"))
                else AppButton(stringResource(Res.string.transfer_submit), onClick = viewModel::send, enabled = state.canSend, busy = state.sending, modifier = Modifier.testTag("transfer-submit"))
            }
        }
    }
}

@Composable
private fun MemberOption(member: TeamMember, selected: Boolean, onClick: () -> Unit) {
    val colors = Ostrom.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp).testTag("recipient-${member.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(member.username, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(member.username, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(member.id, style = MetaStyle, color = colors.muted)
        }
        if (member.administrator) Chip(stringResource(Res.string.transfer_administrator), tone = Tone.MUTED)
        Box(Modifier.size(20.dp).border(1.5.dp, if (selected) colors.accent else colors.line, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
        }
    }
}
