// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/SessionScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowUp
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.Stop
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.AttachButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.KindChips
import run.ostrom.app.presentation.components.hostName
import run.ostrom.app.presentation.components.ConfirmingLinks
import run.ostrom.app.presentation.components.Dot
import run.ostrom.app.presentation.components.IconButtonKind
import run.ostrom.app.presentation.components.LinkButton
import run.ostrom.app.presentation.components.MessageFiles
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.PendingFileChips
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.dismissKeyboardOnTap
import run.ostrom.app.presentation.components.fileProblemText
import run.ostrom.app.presentation.files.FileDropArea
import run.ostrom.app.presentation.files.rememberFilePicker
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.transcript.Subagent
import run.ostrom.app.presentation.transcript.TranscriptItem
import run.ostrom.app.presentation.transcript.TranscriptItemView
import run.ostrom.app.presentation.transcript.TranscriptSeparator
import run.ostrom.app.presentation.transcript.atMostBubbleWidth
import run.ostrom.app.presentation.transcript.backgroundCommands
import run.ostrom.app.presentation.transcript.indexOfEvent
import run.ostrom.app.presentation.transcript.liveTasks
import run.ostrom.app.presentation.transcript.subagentsOf
import run.ostrom.app.presentation.transcript.threadEvents
import run.ostrom.app.presentation.transcript.transcriptItems
import run.ostrom.app.presentation.viewmodels.CloudSessionUiState
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.LiveState
import run.ostrom.app.presentation.viewmodels.SessionNotice
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_cancel
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.cloud_resume
import run.ostrom.app.resources.composer_ended
import run.ostrom.app.resources.composer_hint
import run.ostrom.app.resources.composer_send
import run.ostrom.app.resources.conversation_bypass_off
import run.ostrom.app.resources.conversation_ended
import run.ostrom.app.resources.conversation_interrupt
import run.ostrom.app.resources.error_cloud_effort
import run.ostrom.app.resources.error_cloud_model
import run.ostrom.app.resources.files_dismiss
import run.ostrom.app.resources.files_drop
import run.ostrom.app.resources.files_not_ready
import run.ostrom.app.resources.message_files_invalid
import run.ostrom.app.resources.message_files_too_large
import run.ostrom.app.resources.message_files_unavailable
import run.ostrom.app.resources.message_files_unsupported
import run.ostrom.app.resources.notice_nothing_running
import run.ostrom.app.resources.notice_permission_answered
import run.ostrom.app.resources.notice_permission_denied
import run.ostrom.app.resources.notice_permission_ended
import run.ostrom.app.resources.notice_permission_expired
import run.ostrom.app.resources.notice_permission_input_unseen
import run.ostrom.app.resources.notice_permission_invalid
import run.ostrom.app.resources.notice_permission_rejected
import run.ostrom.app.resources.pending_discard
import run.ostrom.app.resources.pending_failed
import run.ostrom.app.resources.pending_refused
import run.ostrom.app.resources.pending_retry
import run.ostrom.app.resources.pending_sending
import run.ostrom.app.resources.pending_sent
import run.ostrom.app.resources.pending_woke
import run.ostrom.app.resources.session_bypass_on
import run.ostrom.app.resources.session_connecting
import run.ostrom.app.resources.session_details
import run.ostrom.app.resources.session_effort_change
import run.ostrom.app.resources.session_effort_custom
import run.ostrom.app.resources.session_effort_default
import run.ostrom.app.resources.session_effort_hint
import run.ostrom.app.resources.session_effort_title
import run.ostrom.app.resources.session_empty
import run.ostrom.app.resources.session_live
import run.ostrom.app.resources.session_load_older
import run.ostrom.app.resources.session_model_apply
import run.ostrom.app.resources.session_model_change
import run.ostrom.app.resources.session_model_custom
import run.ostrom.app.resources.session_model_default
import run.ostrom.app.resources.session_model_hint
import run.ostrom.app.resources.session_model_title
import run.ostrom.app.resources.session_nothing_older
import run.ostrom.app.resources.session_paused_dock
import run.ostrom.app.resources.session_paused_stream
import run.ostrom.app.resources.session_reconnecting
import run.ostrom.app.resources.session_working
import run.ostrom.app.resources.subagents_back_to_main
import run.ostrom.app.resources.subagents_thread_empty
import run.ostrom.app.resources.composer_local
import run.ostrom.app.resources.session_paused_remote_dock

/**
 * A conversation session (`mode: conversation`): a compact summary (status, model, effort, live stream; the record on
 * demand), the transcript (history pages upward, live events downward while visible), the subagents panel, and a dock
 * with the permission card, Interrupt or Resume and the message field. TeamCode's SessionScreen; Pause, Resume, Stop
 * and the record are the cloud session's own ([record]).
 */
@Composable
internal fun ConversationScreen(
    state: CloudSessionUiState,
    session: CloudSession,
    viewModel: CloudSessionViewModel,
    backLabel: String,
    onBack: (() -> Unit)?,
    title: String,
    memberId: String?,
    navActions: @Composable () -> Unit,
    record: @Composable ColumnScope.() -> Unit,
) {
    val colors = Ostrom.colors
    val messaging = ControlConversationUseCase.canMessage(session)
    // What runs in the session, from the transcript already loaded: no request of its own.
    val events = state.transcript.events
    val live = remember(events, state.agentRuns) { liveTasks(events, state.agentRuns) }
    val activeHint = remember(events, live, state.agents) { subagentsOf(events, state.agentRuns, live?.tasks, 0, state.agents).any { it.active } || !live?.tasks.isNullOrEmpty() }
    val now = rememberTicking(activeHint)
    val work = remember(events, live, state.agentRuns, now, state.agents) {
        RunningWork(subagentsOf(events, state.agentRuns, live?.tasks, now, state.agents), live, live?.tasks?.let { backgroundCommands(it, events) }.orEmpty())
    }
    val byId = remember(work) { work.subagents.associateBy { it.id } }
    val mode = currentSubagentPanelMode()
    val thread = state.thread?.takeIf { it in byId }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var choosingEffort by rememberSaveable { mutableStateOf(false) }
    val layout = subagentLayout(mode, work.hasContent, state.panelOpen, state.panelHidden, thread != null)
    val canStop = state.options.tasks && live?.stoppable == true && messaging && state.agentRuns
    val overlaid = mode == SubagentPanelMode.SCREEN || mode == SubagentPanelMode.OVERLAY
    val openThread = { id: String -> viewModel.openThread(id); if (overlaid) viewModel.closePanel() }
    val togglePanel = { if (overlaid) viewModel.togglePanel() else viewModel.togglePanelHidden() }
    val panel = @Composable { modifier: Modifier, onClose: (() -> Unit)?, onHide: (() -> Unit)? ->
        SubagentPanel(work, thread, state.stopping, canStop, now, onOpen = openThread,
            onMain = { viewModel.backToMain(); if (overlaid) viewModel.closePanel() },
            onStop = viewModel::askStop, modifier = modifier, onClose = onClose, onHide = onHide)
    }
    val today = localEpochDay(now)
    val backToMain = stringResource(Res.string.subagents_back_to_main)
    // Files dragged onto the screen (the desktop) go the way of the paperclip's, while it takes them.
    FileDropArea(enabled = messaging && state.files.canAttach, onDropped = viewModel::attach, modifier = Modifier.fillMaxSize()) { dropping ->
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(colors.background).dismissKeyboardOnTap().testTag("conversation")) {
            NavBar(backLabel, onBack, title) {
                if (work.hasContent) RunningBadge(work, onClick = togglePanel)
                navActions()
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The effort follows the model's rules: the owner, while the session is not over, where the server takes it.
                        val canChangeModel = state.options.model && state.canControl && ControlConversationUseCase.canChangeModel(session)
                        val canChangeEffort = state.options.effort && state.canControl && ControlConversationUseCase.canChangeModel(session)
                        Summary(session, state, onModel = if (canChangeModel) ({ choosingModel = true }) else null, onEffort = if (canChangeEffort) ({ choosingEffort = true }) else null, record = record)
                        if (choosingEffort && canChangeEffort) EffortPickerDialog(
                            current = session.effort, levels = ControlConversationUseCase.effortLevels(session.agent), busy = state.busy,
                            onConfirm = { choosingEffort = false; viewModel.setEffort(it) }, onDismiss = { choosingEffort = false },
                        )
                        if (choosingModel && canChangeModel) ModelPickerDialog(
                            current = session.model, busy = state.busy,
                            onConfirm = { choosingModel = false; viewModel.setModel(it) }, onDismiss = { choosingModel = false },
                        )
                        state.transcriptError?.let { error ->
                            Banner(error.words(), Tone.DANGER, Modifier.testTag("transcript-error"), action = if (!state.gone) {
                                { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::retryTranscript, kind = ButtonKind.SECONDARY, small = true, fill = false) }
                            } else null)
                        }
                        state.notice?.let { notice -> Banner(noticeText(notice), Tone.WARN, Modifier.testTag("notice")) }
                    }
                    if (layout.threadReplacesMain && thread != null) {
                        ThreadBar(byId[thread], backToMain, onBack = viewModel::backToMain, besideMain = false)
                        key(thread) { ConfirmingLinks { Transcript(state, thread, byId, memberId, viewModel, openThread, today, Modifier.weight(1f)) } }
                    } else {
                        key(null) { ConfirmingLinks { Transcript(state, null, byId, memberId, viewModel, openThread.takeIf { work.hasContent }, today, Modifier.weight(1f)) } }
                    }
                }
                if (layout.threadBeside && thread != null) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.line))
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("thread-column")) {
                        ThreadBar(byId[thread], backToMain, onBack = viewModel::backToMain, besideMain = true)
                        key(thread) { ConfirmingLinks { Transcript(state, thread, byId, memberId, viewModel, openThread, today, Modifier.weight(1f)) } }
                    }
                }
                if (layout.panel == PanelPlacement.SIDE) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.line))
                    panel(Modifier.width(SubagentPanelWidth).fillMaxHeight(), null, viewModel::togglePanelHidden)
                }
            }
            Dock(session, state, viewModel)
        }
        when (layout.panel) {
            PanelPlacement.OVERLAY -> SubagentPanelOverlay(viewModel::closePanel) { panel(it, viewModel::closePanel, null) }
            PanelPlacement.SCREEN -> SubagentPanelScreen { panel(it.windowInsetsPadding(WindowInsets.statusBars), viewModel::closePanel, null) }
            else -> Unit
        }
        state.confirmStop?.let { taskId ->
            val name = work.commands.firstOrNull { it.task.id == taskId }?.let { it.command ?: it.task.description }
                ?: work.subagents.firstOrNull { it.taskId == taskId }?.let { "${it.type}: ${it.description}" } ?: taskId
            StopTaskDialog(name, onConfirm = viewModel::confirmStop, onDismiss = viewModel::cancelStop)
        }
        if (dropping) DropHint()
    }
    }
}

/** Over the conversation while files are dragged onto it. */
@Composable
private fun DropHint() {
    val colors = Ostrom.colors
    Box(Modifier.fillMaxSize().background(colors.shade).padding(24.dp).testTag("drop-files"), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.files_drop), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.panel).border(1.dp, colors.accent, RoundedCornerShape(12.dp)).padding(20.dp))
    }
}

@Composable
private fun noticeText(notice: SessionNotice): String = when (notice) {
    is SessionNotice.Text -> notice.text.words()
    SessionNotice.NothingRunning -> stringResource(Res.string.notice_nothing_running)
    is SessionNotice.PermissionClosed -> stringResource(
        when (notice.code) {
            "permission_request_answered" -> Res.string.notice_permission_answered
            "permission_request_expired" -> Res.string.notice_permission_expired
            else -> Res.string.notice_permission_ended
        },
    )
    is SessionNotice.PermissionInvalid -> if (notice.code == "permission_input_unseen") stringResource(Res.string.notice_permission_input_unseen)
        else stringResource(Res.string.notice_permission_invalid, notice.code ?: "400")
    SessionNotice.PermissionDenied -> stringResource(Res.string.notice_permission_denied)
    is SessionNotice.PermissionRejected -> stringResource(Res.string.notice_permission_rejected, notice.reason ?: "rejected")
    SessionNotice.FilesNotReady -> stringResource(Res.string.files_not_ready)
}

/** The bypass chip's "turn off", for the owner where the server takes it. */
@Composable
internal fun RevokeBypassButton(state: CloudSessionUiState, viewModel: CloudSessionViewModel) {
    val session = state.session ?: return
    if (!state.options.bypass || !state.canControl || !session.permissionBypass || session.status.ended) return
    AppButton(stringResource(Res.string.conversation_bypass_off), onClick = viewModel::revokeBypass, kind = ButtonKind.SECONDARY, small = true, fill = false,
        enabled = !state.busy, modifier = Modifier.testTag("revoke-bypass"))
}

/**
 * The model of the session's agent, like `/model` in a terminal: any id typed in (the server checks it). Ostrom serves no
 * list of models (TeamCode's picker offers the team's), so the field is the picker. Switching is offered once another
 * model is typed.
 */
@Composable
private fun ModelPickerDialog(current: String?, busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Ostrom.colors
    var chosen by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val sent = chosen.trim()
    val valid = ControlConversationUseCase.validModel(sent)
    val canSwitch = valid && sent != current && !busy
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag("model-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.session_model_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.session_model_hint), color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            AppTextField(
                chosen, { chosen = it }, label = stringResource(Res.string.session_model_custom), mono = true, error = !valid && chosen.isNotEmpty(),
                hint = if (!valid && chosen.isNotEmpty()) stringResource(Res.string.error_cloud_model) else null,
                tag = "model-id", onSubmit = { if (canSwitch) onConfirm(sent) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                AppButton(stringResource(Res.string.session_model_apply), onClick = { onConfirm(sent) }, enabled = canSwitch, modifier = Modifier.weight(1f).testTag("model-apply"))
            }
        }
    }
}

/**
 * The effort of the session's agent: the usual levels of its agent as chips, or any other value typed in. Switching is
 * offered once another effort is chosen; the next turn uses it.
 */
@Composable
private fun EffortPickerDialog(current: String?, levels: List<String>, busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Ostrom.colors
    var chosen by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val sent = chosen.trim()
    val valid = ControlConversationUseCase.validEffort(sent)
    val canSwitch = valid && sent != current && !busy
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag("effort-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.session_effort_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.session_effort_hint), color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (level in levels) ChoiceChip(level, selected = sent == level, onClick = { chosen = level }, tag = "effort-option-$level")
            }
            AppTextField(
                chosen, { chosen = it }, label = stringResource(Res.string.session_effort_custom), mono = true, error = !valid && chosen.isNotEmpty(),
                hint = if (!valid && chosen.isNotEmpty()) stringResource(Res.string.error_cloud_effort) else null,
                tag = "effort-id", onSubmit = { if (canSwitch) onConfirm(sent) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                AppButton(stringResource(Res.string.session_model_apply), onClick = { onConfirm(sent) }, enabled = canSwitch, modifier = Modifier.weight(1f).testTag("effort-apply"))
            }
        }
    }
}

/** Status, model, effort and stream; a tap shows the session's record (its actions, recipe and GitHub access). */
@Composable
private fun Summary(session: CloudSession, state: CloudSessionUiState, onModel: (() -> Unit)?, onEffort: (() -> Unit)?, record: @Composable ColumnScope.() -> Unit) {
    val colors = Ostrom.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("summary"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.clickable(role = Role.Button) { expanded = !expanded }.testTag("summary-toggle"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                CloudStatusChip(session)
                // The model that runs; for the owner it opens the picker, like `/model` in a terminal.
                if (session.model != null || onModel != null) {
                    val label = session.model ?: stringResource(Res.string.session_model_default)
                    val changeLabel = stringResource(Res.string.session_model_change)
                    Chip(label, Modifier.testTag("model-chip").let { if (onModel != null) it.clickable(role = Role.Button, onClickLabel = changeLabel, onClick = onModel) else it }, tone = Tone.MUTED)
                }
                // The effort, the same way: for the owner it opens its picker.
                if (session.effort != null || onEffort != null) {
                    val label = session.effort ?: stringResource(Res.string.session_effort_default)
                    val changeLabel = stringResource(Res.string.session_effort_change)
                    Chip(label, Modifier.testTag("effort-chip").let { if (onEffort != null) it.clickable(role = Role.Button, onClickLabel = changeLabel, onClick = onEffort) else it }, tone = Tone.MUTED)
                }
                if (session.remote) KindChips(session)
                if (session.permissionBypass && state.options.bypass) Chip(stringResource(Res.string.session_bypass_on), modifier = Modifier.testTag("bypass-chip"))
                LiveLabel(state)
            }
            Icon(
                if (expanded) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, contentDescription = stringResource(Res.string.session_details),
                tint = colors.muted, modifier = Modifier.size(14.dp),
            )
        }
        if (!expanded) {
            val line = listOfNotNull(session.agent, session.effort, session.host?.directoryName, session.repo?.substringAfter("://")?.removeSuffix(".git"))
            Text(line.joinToString(" · "), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("summary-record"), verticalArrangement = Arrangement.spacedBy(12.dp)) { record() }
        }
    }
}

@Composable
private fun LiveLabel(state: CloudSessionUiState) {
    val colors = Ostrom.colors
    // Stopped by an error, the error says why: "paused in the background" would be wrong.
    if (state.live == LiveState.STOPPED && state.transcriptError != null) return
    val (text, color) = when (state.live) {
        LiveState.CONNECTING -> Res.string.session_connecting to colors.muted
        LiveState.LIVE -> Res.string.session_live to colors.accentText
        LiveState.RECONNECTING -> Res.string.session_reconnecting to colors.warn
        LiveState.ENDED -> Res.string.conversation_ended to colors.muted
        LiveState.STOPPED -> Res.string.session_paused_stream to colors.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.testTag("live")) {
        if (state.live == LiveState.LIVE) Dot(color, size = 6.dp)
        Text(stringResource(text).uppercase(), style = MetaStyle.copy(fontSize = 11.sp, letterSpacing = 0.06.em), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The main agent's thread ([thread] null), or the subagent [thread]'s own: its events only, a subagent shown as a card
 * that opens its thread ([onOpenSubagent]). Each thread has its own list state ([key] by the caller), so following the
 * newest message and keeping the reading position work in each.
 */
@Composable
private fun Transcript(
    state: CloudSessionUiState, thread: String?, subagents: Map<String, Subagent>, memberId: String?, viewModel: CloudSessionViewModel,
    onOpenSubagent: ((String) -> Unit)?, today: Long, modifier: Modifier,
) {
    val colors = Ostrom.colors
    val items = remember(state.transcript.events, thread) { transcriptItems(threadEvents(state.transcript.events, thread)) }
    val listState = rememberLazyListState()
    // The transcript opens on its last message and follows the newest one while the member stays at the bottom; scrolled
    // up to read older ones, the position is left alone until they come back down.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // Only when a scroll ends: the first value is no gesture, and a transcript measured before this ran must still be pinned.
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling -> if (!scrolling) follow = !listState.canScrollForward }
    }
    // Older events arrive above the ones being read: the list keeps the member on the same event, whatever the items' keys became.
    var shownItems by remember { mutableStateOf<List<TranscriptItem>?>(null) }
    SideEffect {
        if (shownItems !== items) {
            if (!follow) listState.keepReading(items)
            shownItems = items
        }
    }
    // Hidden until the first page is pinned to the bottom: the transcript never shows its top, then jumps.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size }, listState.canScrollForward) }
            .collect { (count, _, below) ->
                // Never while the member scrolls: their gesture wins, and decides whether to follow when it ends.
                if (follow && below && count > 0 && !listState.isScrollInProgress) listState.requestScrollToItem(count - 1, 1_000_000)
            }
    }
    LaunchedEffect(state.loaded, items.isEmpty()) {
        if (!state.loaded) return@LaunchedEffect
        // One frame for the pinning above to run on the loaded page.
        withFrameNanos { }
        withFrameNanos { }
        settled = true
    }
    LazyColumn(
        modifier.fillMaxWidth().alpha(if (settled || !state.loaded) 1f else 0f).testTag("transcript"), state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item(key = "top") {
            val older = state.olderOf(thread)
            // Reaching the top loads the page above by itself, like a messaging app, once per cursor; a failed page stops
            // the chain and offers the button, so nothing loops.
            val failed = state.transcriptError != null && !state.loadingOlderOf(thread)
            if (older != null && settled && !failed) LaunchedEffect(older, thread) { viewModel.loadOlder() }
            when {
                state.loadingOlderOf(thread) -> BusyRow(true)
                older != null && failed -> AppButton(stringResource(Res.string.session_load_older), onClick = viewModel::loadOlder, kind = ButtonKind.GHOST, small = true)
                older != null -> BusyRow(true)
                // A subagent's thread starts with its call: "nothing older" speaks of the whole conversation.
                thread == null && state.loaded && items.isNotEmpty() -> TranscriptSeparator(stringResource(Res.string.session_nothing_older))
                !state.threadLoaded(thread) -> BusyRow(state.transcriptError == null)
            }
        }
        items(items, key = { it.key }) { TranscriptItemView(it, memberId, subagents, onOpenSubagent, today) }
        item(key = "bottom") {
            if (state.loaded && items.isEmpty()) {
                Text(stringResource(if (thread == null) Res.string.session_empty else Res.string.subagents_thread_empty),
                    modifier = Modifier.fillMaxWidth().padding(16.dp), color = colors.muted, textAlign = TextAlign.Center)
            }
            // Messages go to the main agent: they show in its thread.
            if (thread == null) state.pending.forEach { PendingBubble(it, viewModel) }
        }
    }
}

/**
 * Called as [items] replace the ones the list was laid out with (before the next measure): puts the list back on the
 * event its first visible row shows, at the same distance from the top, when that row moved to another index.
 */
private fun LazyListState.keepReading(items: List<TranscriptItem>) {
    val info = layoutInfo
    // The first row that is an event: the "top" and "bottom" rows have no sequence.
    val reading = info.visibleItemsInfo.firstOrNull { it.key is Long } ?: return
    val index = items.indexOfEvent(reading.key as Long)
    if (index < 0 || index + 1 == reading.index) return
    requestScrollToItem(index + 1, 0)
}

/** The bottom dock: the permission card, what the agent does (Interrupt) or that the session is paused (Resume), then the message field. */
@Composable
private fun Dock(session: CloudSession, state: CloudSessionUiState, viewModel: CloudSessionViewModel) {
    val colors = Ostrom.colors
    Column(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
        Column(
            Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ControlConversationUseCase.canMessage(session)) {
                    Text(stringResource(if (session.local && !session.status.ended) Res.string.composer_local else Res.string.composer_ended), color = colors.muted, fontSize = 13.5.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("composer-ended"))
                    return@Column
                }
                // The agent waits on it: shown above everything else, scrolled within a bounded height.
                state.permission?.let { card ->
                    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { PermissionCardView(card, viewModel) }
                }
                val paused = session.status == CloudSessionStatus.PAUSED || session.status == CloudSessionStatus.PAUSING
                when {
                    state.running -> DockStatus(stringResource(Res.string.session_working), colors.accentText, dot = true, tag = "running") {
                        AppButton(stringResource(Res.string.conversation_interrupt), onClick = viewModel::interrupt, enabled = !state.busy, busy = state.busy, kind = ButtonKind.SECONDARY,
                            small = true, fill = false, icon = PhosphorIcons.Regular.Stop, modifier = Modifier.testTag("interrupt"))
                    }
                    paused -> DockStatus(
                        if (session.remote) stringResource(Res.string.session_paused_remote_dock, hostName(session.host)) else stringResource(Res.string.session_paused_dock),
                        colors.warn, dot = false, tag = "paused",
                    ) {
                        if (session.canResume) {
                            AppButton(stringResource(Res.string.cloud_resume), onClick = viewModel::resume, enabled = state.acting == null, busy = state.acting != null,
                                kind = ButtonKind.SECONDARY, small = true, fill = false, icon = PhosphorIcons.Regular.Play, modifier = Modifier.testTag("dock-resume"))
                        }
                    }
                }
                state.files.notice?.let { problem ->
                    Banner(fileProblemText(problem), Tone.WARN, Modifier.testTag("file-notice"), action = {
                        AppButton(stringResource(Res.string.files_dismiss), onClick = viewModel::dismissFileNotice, kind = ButtonKind.GHOST, small = true, fill = false)
                    })
                }
                PendingFileChips(state.files.files, onRetry = viewModel::retryFile, onRemove = viewModel::removeFile)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Only once the server announced `runners.cloud.files.v1`: an older server shows the composer as before.
                    if (state.files.supported) {
                        val picker = rememberFilePicker(onPicked = viewModel::attach, onFailed = viewModel::pickFailed)
                        AttachButton(state.files, picker, onUnavailable = viewModel::explainFiles)
                    }
                    AppTextField(
                        state.draft, viewModel::onDraftChange, singleLine = false, maxLines = 6, placeholder = stringResource(Res.string.composer_hint),
                        modifier = Modifier.weight(1f), tag = "composer", shape = RoundedCornerShape(22.dp), minHeight = 44.dp,
                        onSubmit = { if (state.canSend) viewModel.send() },
                        // A file or a screenshot pasted into the field goes the way of the paperclip's, when files are on.
                        onPasteFiles = if (state.files.supported && state.files.canAttach) viewModel::attach else null, onPasteFailed = viewModel::pickFailed,
                    )
                    AppIconButton(PhosphorIcons.Regular.ArrowUp, stringResource(Res.string.composer_send), onClick = viewModel::send, kind = IconButtonKind.FILLED,
                        enabled = state.canSend, modifier = Modifier.testTag("send"))
                }
            }
        }
    }
}

@Composable
private fun DockStatus(text: String, color: Color, dot: Boolean, tag: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dot) Dot(color)
        Text(text, color = color, fontSize = 13.5.sp, fontWeight = if (dot) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun PendingBubble(message: PendingMessage, viewModel: CloudSessionViewModel) {
    val colors = Ostrom.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("pending-${message.clientMessageId}"), horizontalAlignment = Alignment.End) {
        if (message.text.isNotEmpty()) Text(
            message.text,
            modifier = Modifier.atMostBubbleWidth().alpha(0.7f).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(colors.bubble).padding(horizontal = 12.dp, vertical = 9.dp),
            color = colors.onBubble,
            fontSize = 14.5.sp,
        )
        MessageFiles(message.files, Modifier.padding(top = 4.dp))
        val failed = message.state == PendingMessage.State.FAILED || message.state == PendingMessage.State.REFUSED
        val status = when (message.state) {
            PendingMessage.State.SENDING -> stringResource(Res.string.pending_sending)
            PendingMessage.State.SENT -> stringResource(if (message.woke) Res.string.pending_woke else Res.string.pending_sent)
            PendingMessage.State.FAILED -> stringResource(Res.string.pending_failed, message.reason ?: "")
            PendingMessage.State.REFUSED -> stringResource(Res.string.pending_refused, messageRefusal(message.reason))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, fontSize = 12.sp, color = if (failed) colors.danger else colors.muted, modifier = Modifier.testTag("pending-status-${message.clientMessageId}"))
            if (failed) {
                if (message.state == PendingMessage.State.FAILED) {
                    LinkButton(stringResource(Res.string.pending_retry), onClick = { viewModel.retry(message.clientMessageId) }, modifier = Modifier.testTag("retry-${message.clientMessageId}"))
                }
                LinkButton(stringResource(Res.string.pending_discard), onClick = { viewModel.discard(message.clientMessageId) }, color = colors.muted)
            }
        }
    }
}

/** A message's refusal: the files' codes in words, any other reason as the server's code. */
@Composable
private fun messageRefusal(reason: String?): String = when (reason) {
    "file_unavailable" -> stringResource(Res.string.message_files_unavailable)
    "files_too_large" -> stringResource(Res.string.message_files_too_large)
    "files_unsupported" -> stringResource(Res.string.message_files_unsupported)
    "invalid_files" -> stringResource(Res.string.message_files_invalid)
    else -> reason.orEmpty()
}
