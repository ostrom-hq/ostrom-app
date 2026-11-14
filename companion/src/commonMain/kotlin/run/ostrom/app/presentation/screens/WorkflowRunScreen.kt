// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowRunScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.RunVisit
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.SectionTitle
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.WorkflowRunViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.workflow_open_parent
import run.ostrom.app.resources.workflow_open_run
import run.ostrom.app.resources.workflow_open_session
import run.ostrom.app.resources.workflow_reconnecting
import run.ostrom.app.resources.workflow_run_inputs
import run.ostrom.app.resources.workflow_run_no_visits
import run.ostrom.app.resources.workflow_run_of_version
import run.ostrom.app.resources.workflow_run_refresh
import run.ostrom.app.resources.workflow_run_result
import run.ostrom.app.resources.workflow_run_timeline
import run.ostrom.app.resources.workflow_run_title
import run.ostrom.app.resources.workflow_visit_finished
import run.ostrom.app.resources.workflow_visit_outcome
import run.ostrom.app.resources.workflow_visit_output
import run.ostrom.app.resources.workflow_visit_started
import run.ostrom.app.resources.workflow_visit_waiting
import run.ostrom.app.resources.workflows_title

/**
 * One run: its status and failure, its inputs, the visits as a timeline (node, kind, status, outcome, output, times,
 * the session it ran in, the sub-workflow's run), and its result. Read again at each change of the run the feed
 * announces. Starting and cancelling runs stay in the console.
 */
@Composable
fun WorkflowRunScreen(
    viewModel: WorkflowRunViewModel,
    onBack: (() -> Unit)?,
    onOpenSession: ((sessionId: String) -> Unit)?,
    onOpenRun: (runId: String) -> Unit,
    backLabel: String = stringResource(Res.string.workflows_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val now = rememberNow()
    val colors = Ostrom.colors
    val run = state.run
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, run?.workflowName ?: stringResource(Res.string.workflow_run_title)) {
            AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.workflow_run_refresh), onClick = viewModel::load, enabled = !state.loading,
                modifier = Modifier.testTag("workflow-run-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                .testTag("workflow-run-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.reconnecting) Banner(stringResource(Res.string.workflow_reconnecting), Tone.WARN, Modifier.testTag("workflow-run-reconnecting"))
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-run-error"), action = if (!state.gone) {
                    { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("workflow-run-retry")) }
                } else null)
            }
            BusyRow(state.loading && run == null)
            if (run != null) {
                val today = localEpochDay(now)
                AppCard(Modifier.testTag("workflow-run-overview")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(runStatusWords(run.status), tone = runStatusTone(run.status), dot = run.status.active, modifier = Modifier.testTag("workflow-run-status"))
                        Text(stringResource(Res.string.workflow_run_of_version, run.workflowName, run.workflowVersion), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RunTimes(run, now, today, Modifier.testTag("workflow-run-duration"))
                    Text(listOfNotNull(run.startedBy, run.source, run.repository).joinToString(" · "), style = MetaStyle, color = colors.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val session = run.sessionId
                        if (session != null && onOpenSession != null) {
                            AppButton(stringResource(Res.string.workflow_open_session), onClick = { onOpenSession(session) }, kind = ButtonKind.SECONDARY, small = true, fill = false,
                                modifier = Modifier.testTag("workflow-run-session"))
                        }
                        run.parentRunId?.let { parent ->
                            AppButton(stringResource(Res.string.workflow_open_parent), onClick = { onOpenRun(parent) }, kind = ButtonKind.SECONDARY, small = true, fill = false,
                                modifier = Modifier.testTag("workflow-run-parent"))
                        }
                    }
                }
                if (run.status == RunStatus.FAILED) (run.failure ?: run.failureCode)?.let { Banner(it, Tone.DANGER, Modifier.testTag("workflow-run-failure")) }
                if (run.inputs.isNotEmpty()) {
                    SectionTitle(stringResource(Res.string.workflow_run_inputs))
                    AppCard(Modifier.testTag("workflow-run-inputs")) {
                        run.inputs.forEach { (id, value) -> Text("$id: $value", style = MonospaceStyle, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
                SectionTitle(stringResource(Res.string.workflow_run_timeline))
                if (run.visits.isEmpty()) Text(stringResource(Res.string.workflow_run_no_visits), color = colors.muted, fontSize = 14.sp)
                run.visits.forEach { visit -> VisitCard(visit, today, onOpenSession, onOpenRun) }
                run.output?.takeIf { it.isNotBlank() && run.status == RunStatus.SUCCEEDED }?.let {
                    SectionTitle(stringResource(Res.string.workflow_run_result))
                    AppCard(Modifier.testTag("workflow-run-result")) { SelectionContainer { Text(it, color = colors.text, fontSize = 14.sp) } }
                }
            }
        }
    }
}

@Composable
private fun VisitCard(visit: RunVisit, today: Long, onOpenSession: ((String) -> Unit)?, onOpenRun: (String) -> Unit) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("workflow-visit-${visit.seq}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(visit.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(visit.status, tone = when {
                visit.active -> Tone.ACCENT
                visit.status == "failed" -> Tone.DANGER
                visit.status == "cancelled" -> Tone.MUTED
                else -> Tone.NEUTRAL
            }, dot = visit.active, modifier = Modifier.testTag("workflow-visit-status-${visit.seq}"))
        }
        Text(nodeKindWords(visit.kind), style = MetaStyle, color = colors.muted)
        visit.outcome?.let { Text(stringResource(Res.string.workflow_visit_outcome, it), color = colors.text, fontSize = 13.sp, modifier = Modifier.testTag("workflow-visit-outcome-${visit.seq}")) }
        visit.output?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(Res.string.workflow_visit_output), style = MetaStyle, color = colors.muted)
            SelectionContainer { Text(it, color = colors.text, fontSize = 13.sp, maxLines = 20, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("workflow-visit-output-${visit.seq}")) }
        }
        visit.startedAt?.let { Text(stringResource(Res.string.workflow_visit_started, formatEventTime(it, today)), style = MetaStyle, color = colors.muted) }
        visit.endedAt?.let { Text(stringResource(Res.string.workflow_visit_finished, formatEventTime(it, today)), style = MetaStyle, color = colors.muted) }
        if (visit.active && (visit.waitId != null || visit.gateId != null)) {
            Text(stringResource(Res.string.workflow_visit_waiting), style = MetaStyle, color = colors.warn, modifier = Modifier.testTag("workflow-visit-waiting-${visit.seq}"))
        }
        val session = visit.sessionId
        val child = visit.childRunId
        if ((session != null && onOpenSession != null) || child != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session != null && onOpenSession != null) {
                AppButton(stringResource(Res.string.workflow_open_session), onClick = { onOpenSession(session) }, kind = ButtonKind.SECONDARY, small = true, fill = false,
                    modifier = Modifier.testTag("workflow-visit-session-${visit.seq}"))
            }
            if (child != null) {
                AppButton(stringResource(Res.string.workflow_open_run), onClick = { onOpenRun(child) }, kind = ButtonKind.SECONDARY, small = true, fill = false,
                    modifier = Modifier.testTag("workflow-visit-child-${visit.seq}"))
            }
        }
    }
}
