// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion ArtifactScreen.kt, WaitCards.kt and PermissionCard.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.Warning
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.ActionKind
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.CheckState
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.domain.models.NoApprove
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.Centered
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.Group
import run.ostrom.app.presentation.components.GroupDivider
import run.ostrom.app.presentation.components.GroupRow
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.RequestUiState
import run.ostrom.app.presentation.viewmodels.RequestViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.approvals_ago_days
import run.ostrom.app.resources.approvals_ago_hours
import run.ostrom.app.resources.approvals_ago_minutes
import run.ostrom.app.resources.approvals_ago_now
import run.ostrom.app.resources.approvals_in_days
import run.ostrom.app.resources.approvals_in_hours
import run.ostrom.app.resources.approvals_in_minutes
import run.ostrom.app.resources.approvals_retry
import run.ostrom.app.resources.approvals_title
import run.ostrom.app.resources.request_agent
import run.ostrom.app.resources.request_approve
import run.ostrom.app.resources.request_approve_deploy
import run.ostrom.app.resources.request_approve_merge
import run.ostrom.app.resources.request_approve_push
import run.ostrom.app.resources.request_approved_by
import run.ostrom.app.resources.request_approved_by_at
import run.ostrom.app.resources.request_approver
import run.ostrom.app.resources.request_asked_by
import run.ostrom.app.resources.request_asked_by_me
import run.ostrom.app.resources.request_back
import run.ostrom.app.resources.request_cannot_approve
import run.ostrom.app.resources.request_change
import run.ostrom.app.resources.request_check_failure
import run.ostrom.app.resources.request_check_pending
import run.ostrom.app.resources.request_check_skipped
import run.ostrom.app.resources.request_check_success
import run.ostrom.app.resources.request_check_unknown
import run.ostrom.app.resources.request_checks
import run.ostrom.app.resources.request_command
import run.ostrom.app.resources.request_commits
import run.ostrom.app.resources.request_confirm_approve
import run.ostrom.app.resources.request_confirm_decline
import run.ostrom.app.resources.request_console_not_opened
import run.ostrom.app.resources.request_copied
import run.ostrom.app.resources.request_copy_link
import run.ostrom.app.resources.request_decline
import run.ostrom.app.resources.request_declined_by
import run.ostrom.app.resources.request_declined_by_at
import run.ostrom.app.resources.request_diff_stats
import run.ostrom.app.resources.request_error
import run.ostrom.app.resources.request_evidence
import run.ostrom.app.resources.request_expired
import run.ostrom.app.resources.request_expired_at
import run.ostrom.app.resources.request_expires
import run.ostrom.app.resources.request_gate
import run.ostrom.app.resources.request_head
import run.ostrom.app.resources.request_note
import run.ostrom.app.resources.request_open_console
import run.ostrom.app.resources.request_people
import run.ostrom.app.resources.request_problem_already_decided
import run.ostrom.app.resources.request_problem_biometrics_failed
import run.ostrom.app.resources.request_problem_biometrics_unavailable
import run.ostrom.app.resources.request_problem_changed
import run.ostrom.app.resources.request_problem_failed
import run.ostrom.app.resources.request_problem_forbidden
import run.ostrom.app.resources.request_problem_offline
import run.ostrom.app.resources.request_problem_org_required
import run.ostrom.app.resources.request_problem_self_approval
import run.ostrom.app.resources.request_problem_session_expired
import run.ostrom.app.resources.request_problem_step_up
import run.ostrom.app.resources.request_problem_unlock_again
import run.ostrom.app.resources.request_problem_unlock_offline
import run.ostrom.app.resources.request_problem_unlock_refused
import run.ostrom.app.resources.request_reason
import run.ostrom.app.resources.request_ref
import run.ostrom.app.resources.request_repository
import run.ostrom.app.resources.request_reread
import run.ostrom.app.resources.request_self_approved
import run.ostrom.app.resources.request_send_decline
import run.ostrom.app.resources.request_target
import run.ostrom.app.resources.request_technical
import run.ostrom.app.resources.request_title
import run.ostrom.app.resources.request_truncated
import run.ostrom.app.resources.request_unknown_status

/**
 * One request for a yes under a back bar: who asks, what it covers (the gate, the command, the commit and its size),
 * the commits, checks and evidence, all before any button. Then the answer: Approve after the device's unlock, or
 * Decline with the reason the agent reads. A request that changed under the screen holds every answer until the person
 * says they read it again. Read again each time the screen shows.
 */
@Composable
fun RequestScreen(viewModel: RequestViewModel, onBack: (() -> Unit)?) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val colors = Ostrom.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        NavBar(stringResource(Res.string.approvals_title), onBack, stringResource(Res.string.request_title))
        Centered(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("request"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val approval = state.approval
                val error = state.error
                when {
                    approval != null -> {
                        // A reading that failed after one succeeded: what is shown may be out of date.
                        if (error != null) Banner(error.words(), Tone.DANGER, Modifier.testTag("request-stale")) {
                            AppButton(stringResource(Res.string.approvals_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false)
                        }
                        Head(approval)
                        Evidence(approval)
                        if (state.open) Actions(state, approval, viewModel)
                    }
                    error != null -> {
                        EmptyState(
                            PhosphorIcons.Regular.Warning, stringResource(Res.string.request_error), error.words(), Modifier.testTag("request-error"),
                            dashed = false, iconTint = colors.warn,
                        ) {
                            AppButton(stringResource(Res.string.approvals_retry), onClick = viewModel::load, modifier = Modifier.testTag("request-retry"))
                            // The console names the organization the app does not know yet: the request opens there.
                            if (state.orgRequired) ConsoleButton(state, viewModel, ButtonKind.SECONDARY)
                        }
                        ConsoleLink(state, viewModel)
                    }
                    else -> BusyRow(true)
                }
            }
        }
    }
}

/** The request's state, its title, and who answered it once answered. */
@Composable
private fun Head(approval: Approval) {
    val colors = Ostrom.colors
    val now = rememberNow()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(approval.status)
        Text(approval.title, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("request-title"))
        val outcome = outcomeLine(approval, now)
        if (outcome != null) Text(outcome, color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("request-outcome"))
        val note = approval.note
        if (!note.isNullOrBlank() && !approval.open) Text(stringResource(Res.string.request_note, note), color = colors.muted, fontSize = 14.sp, modifier = Modifier.testTag("request-note"))
    }
}

@Composable
private fun outcomeLine(approval: Approval, now: Long): String? {
    val by = approval.decidedBy
    val at = approval.decidedAt
    val line = when {
        approval.status == ApprovalStatus.EXPIRED -> approval.expiresAt?.let { stringResource(Res.string.request_expired_at, timeOf(it, now)) } ?: stringResource(Res.string.request_expired)
        approval.status == ApprovalStatus.UNKNOWN -> stringResource(Res.string.request_unknown_status)
        by.isNullOrBlank() -> null
        approval.status == ApprovalStatus.APPROVED -> at?.let { stringResource(Res.string.request_approved_by_at, by, timeOf(it, now)) } ?: stringResource(Res.string.request_approved_by, by)
        approval.status == ApprovalStatus.REJECTED -> at?.let { stringResource(Res.string.request_declined_by_at, by, timeOf(it, now)) } ?: stringResource(Res.string.request_declined_by, by)
        else -> null
    } ?: return null
    return if (approval.selfApproved && approval.status == ApprovalStatus.APPROVED) "$line · ${stringResource(Res.string.request_self_approved)}" else line
}

/** Everything the person reads before any button. */
@Composable
private fun Evidence(approval: Approval) {
    val colors = Ostrom.colors
    val now = rememberNow()
    val agent = listOfNotNull(approval.who?.agent, approval.who?.model).filter { it.isNotBlank() }.joinToString(" · ")
    if (approval.askedBy.isNotEmpty() || agent.isNotEmpty()) {
        Section(Res.string.request_asked_by) {
            if (approval.askedBy.isNotEmpty()) Field(Res.string.request_people, approval.askedBy.joinToString(", "), "field-people")
            if (agent.isNotEmpty()) Field(Res.string.request_agent, agent, "field-agent")
        }
    }
    Section(Res.string.request_target) {
        approval.repo?.takeIf { it.isNotBlank() }?.let { Field(Res.string.request_repository, it, "field-repository") }
        approval.gate?.let { Field(Res.string.request_gate, "${it.workflowName} · ${it.label}", "field-gate") }
        approval.what?.command?.takeIf { it.isNotBlank() }?.let { Field(Res.string.request_command, it, "field-command", monospace = true) }
        // The head is the commit the yes lets through when the server names the action.
        (approval.action?.headSha ?: approval.sha)?.takeIf { it.isNotBlank() }?.let { Field(Res.string.request_head, it.take(7), "field-head", monospace = true) }
        approval.action?.ref?.takeIf { it.isNotBlank() }?.let { Field(Res.string.request_ref, it, "field-ref", monospace = true) }
        approval.diffStats?.let { Field(Res.string.request_change, stringResource(Res.string.request_diff_stats, it.files, it.additions, it.deletions), "field-change") }
        Field(Res.string.request_approver, approval.requestedFrom, "field-approver")
        approval.expiresAt?.let { Field(Res.string.request_expires, timeOf(it, now), "field-expires") }
    }
    if (approval.commits.isNotEmpty()) {
        Section(Res.string.request_commits) {
            approval.commits.forEach { commit ->
                GroupRow {
                    Text(commit.sha.take(7), style = MetaStyle, color = colors.muted)
                    Column(Modifier.weight(1f)) {
                        commit.title?.let { Text(it, color = colors.text, fontSize = 14.sp) }
                        commit.author?.let { Text(it, style = MetaStyle, color = colors.muted) }
                    }
                }
            }
        }
    }
    if (approval.checks.isNotEmpty()) {
        Section(Res.string.request_checks) {
            // Two checks may share a name (a matrix, two workflows): each one is shown.
            approval.checks.forEach { check ->
                GroupRow {
                    Text(check.name, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val (label, color) = when (check.state) {
                        CheckState.SUCCESS -> Res.string.request_check_success to colors.accentText
                        CheckState.FAILURE -> Res.string.request_check_failure to colors.danger
                        CheckState.PENDING -> Res.string.request_check_pending to colors.warn
                        CheckState.SKIPPED -> Res.string.request_check_skipped to colors.muted
                        CheckState.UNKNOWN -> Res.string.request_check_unknown to colors.muted
                    }
                    Text(stringResource(label), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (approval.evidence.isNotEmpty()) {
        Section(Res.string.request_evidence) {
            approval.evidence.forEach { line ->
                GroupRow {
                    Text(line.label, color = colors.muted, fontSize = 14.sp)
                    Text(line.value, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    Section(Res.string.request_technical) {
        GroupRow {
            SelectionContainer {
                Text(listOfNotNull(approval.id, approval.sha, approval.shownDigest).filter { it.isNotBlank() }.joinToString("\n"), style = MetaStyle, color = colors.muted)
            }
        }
    }
}

@Composable
private fun Section(title: StringResource, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(title))
        Group {
            Column(content = content)
        }
    }
}

@Composable
private fun Field(label: StringResource, value: String, tag: String, monospace: Boolean = false) {
    val colors = Ostrom.colors
    GroupRow(Modifier.testTag(tag)) {
        Text(stringResource(label), color = colors.muted, fontSize = 14.sp)
        val modifier = Modifier.weight(1f)
        if (monospace) Text(value, color = colors.text, style = MonospaceStyle, modifier = modifier)
        else Text(value, color = colors.text, fontSize = 14.sp, modifier = modifier)
    }
}

/** The answer, after the evidence: like a permission request's card, its problem above the buttons. */
@Composable
private fun Actions(state: RequestUiState, approval: Approval, viewModel: RequestViewModel) {
    val colors = Ostrom.colors
    val approvePrompt = stringResource(Res.string.request_confirm_approve, approval.title)
    val declinePrompt = stringResource(Res.string.request_confirm_decline, approval.title)
    AppCard(Modifier.testTag("request-actions"), borderColor = colors.accentDim, spacing = 10.dp) {
        if (state.askedByMe) Text(stringResource(Res.string.request_asked_by_me), color = colors.text, fontSize = 13.5.sp, modifier = Modifier.testTag("request-mine"))
        state.shownProblem?.let { problem ->
            Banner(stringResource(problem.words()), Tone.DANGER, Modifier.testTag("request-problem").semantics { liveRegion = LiveRegionMode.Polite })
        }
        // A yes that needs a fresh sign-in, or an organization the app does not know, is given in the console.
        if (state.shownProblem == DecisionProblem.STEP_UP || state.shownProblem == DecisionProblem.ORG_REQUIRED) ConsoleButton(state, viewModel, ButtonKind.PRIMARY)
        ConsoleLink(state, viewModel)
        state.noApprove?.let { why ->
            Text(stringResource(if (why == NoApprove.TRUNCATED) Res.string.request_truncated else Res.string.request_cannot_approve), color = colors.text, fontSize = 13.5.sp,
                modifier = Modifier.testTag("request-no-approve"))
        }
        val busy = state.sending != null
        when {
            state.changed -> AppButton(stringResource(Res.string.request_reread), onClick = viewModel::reread, modifier = Modifier.testTag("request-reread"))
            state.declining -> {
                AppTextField(state.reason, viewModel::setReason, label = stringResource(Res.string.request_reason), singleLine = false, tag = "request-reason")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(stringResource(Res.string.request_back), onClick = viewModel::cancelDecline, kind = ButtonKind.SECONDARY, enabled = !busy, modifier = Modifier.weight(1f).testTag("request-back"))
                    AppButton(stringResource(Res.string.request_send_decline), onClick = { viewModel.decline(declinePrompt) }, kind = ButtonKind.DANGER,
                        enabled = state.reason.isNotBlank(), busy = state.sending == ApprovalAnswer.REJECT, modifier = Modifier.weight(1f).testTag("request-send-decline"))
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton(stringResource(Res.string.request_decline), onClick = viewModel::startDecline, kind = ButtonKind.DANGER, enabled = !busy, modifier = Modifier.weight(1f).testTag("request-decline"))
                if (state.mayApprove) {
                    AppButton(stringResource(approveLabel(approval.action?.kind)), onClick = { viewModel.approve(approvePrompt) }, busy = state.sending == ApprovalAnswer.APPROVE,
                        enabled = !busy, modifier = Modifier.weight(1f).testTag("request-approve"))
                }
            }
        }
    }
}

/** "Approve push", "Approve merge", "Approve deploy": the button names what the yes lets through. */
private fun approveLabel(kind: ActionKind?): StringResource = when (kind) {
    ActionKind.PUSH -> Res.string.request_approve_push
    ActionKind.MERGE -> Res.string.request_approve_merge
    ActionKind.DEPLOY -> Res.string.request_approve_deploy
    else -> Res.string.request_approve
}

@Composable
private fun ConsoleButton(state: RequestUiState, viewModel: RequestViewModel, kind: ButtonKind) {
    val url = state.consoleUrl ?: return
    val browser = LocalUriHandler.current
    AppButton(stringResource(Res.string.request_open_console), icon = PhosphorIcons.Regular.ArrowSquareOut, kind = kind, modifier = Modifier.testTag("request-open-console"), onClick = {
        try {
            browser.openUri(url)
            viewModel.consoleOpened()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // No browser, or a link the system refused: the address is shown to copy.
            viewModel.consoleNotOpened()
        }
    })
}

/** The console did not open: its address, to select or copy and open by hand. */
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the link needs, on every platform.
@Composable
private fun ConsoleLink(state: RequestUiState, viewModel: RequestViewModel) {
    val url = state.consoleUrl
    if (!state.consoleFailed || url == null) return
    val colors = Ostrom.colors
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.request_console_not_opened), color = colors.text, fontSize = 13.5.sp)
        SelectionContainer { Text(url, style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("request-console-url")) }
        AppButton(stringResource(if (state.copied) Res.string.request_copied else Res.string.request_copy_link), onClick = {
            clipboard.setText(AnnotatedString(url))
            viewModel.linkCopied()
        }, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("request-copy-console"))
    }
}

private fun DecisionProblem.words(): StringResource = when (this) {
    DecisionProblem.BIOMETRICS_UNAVAILABLE -> Res.string.request_problem_biometrics_unavailable
    DecisionProblem.BIOMETRICS_FAILED -> Res.string.request_problem_biometrics_failed
    DecisionProblem.UNLOCK_REFUSED -> Res.string.request_problem_unlock_refused
    DecisionProblem.UNLOCK_OFFLINE -> Res.string.request_problem_unlock_offline
    DecisionProblem.STEP_UP -> Res.string.request_problem_step_up
    DecisionProblem.UNLOCK_AGAIN -> Res.string.request_problem_unlock_again
    DecisionProblem.SELF_APPROVAL -> Res.string.request_problem_self_approval
    DecisionProblem.ORG_REQUIRED -> Res.string.request_problem_org_required
    DecisionProblem.CHANGED -> Res.string.request_problem_changed
    DecisionProblem.ALREADY_DECIDED -> Res.string.request_problem_already_decided
    DecisionProblem.FORBIDDEN -> Res.string.request_problem_forbidden
    DecisionProblem.OFFLINE -> Res.string.request_problem_offline
    DecisionProblem.SESSION_EXPIRED -> Res.string.request_problem_session_expired
    DecisionProblem.FAILED -> Res.string.request_problem_failed
}

/** "5 min ago" for a past time, "in 5 min" for a coming one. */
@Composable
private fun timeOf(at: Long, now: Long): String {
    val past = at <= now
    val count = kotlin.math.abs(at - now) / 60_000
    return when {
        past && count < 1 -> stringResource(Res.string.approvals_ago_now)
        // Under a minute ahead reads "in 1 min", never "just now".
        count < 60 -> stringResource(if (past) Res.string.approvals_ago_minutes else Res.string.approvals_in_minutes, count.toInt().coerceAtLeast(1))
        count < 48 * 60 -> stringResource(if (past) Res.string.approvals_ago_hours else Res.string.approvals_in_hours, (count / 60).toInt())
        else -> stringResource(if (past) Res.string.approvals_ago_days else Res.string.approvals_in_days, (count / 1440).toInt())
    }
}
