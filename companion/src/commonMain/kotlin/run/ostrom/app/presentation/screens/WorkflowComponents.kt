// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowComponents.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunFilter
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.workflow_duration_hours
import run.ostrom.app.resources.workflow_duration_minutes
import run.ostrom.app.resources.workflow_duration_seconds
import run.ostrom.app.resources.workflow_node_agent
import run.ostrom.app.resources.workflow_node_check
import run.ostrom.app.resources.workflow_node_gate
import run.ostrom.app.resources.workflow_node_notify
import run.ostrom.app.resources.workflow_node_step
import run.ostrom.app.resources.workflow_node_trigger
import run.ostrom.app.resources.workflow_node_unknown
import run.ostrom.app.resources.workflow_node_wait
import run.ostrom.app.resources.workflow_node_workflow
import run.ostrom.app.resources.workflow_run_ended
import run.ostrom.app.resources.workflow_run_running_for
import run.ostrom.app.resources.workflow_run_started
import run.ostrom.app.resources.workflow_run_step
import run.ostrom.app.resources.workflow_status_cancelled
import run.ostrom.app.resources.workflow_status_failed
import run.ostrom.app.resources.workflow_status_queued
import run.ostrom.app.resources.workflow_status_running
import run.ostrom.app.resources.workflow_status_succeeded
import run.ostrom.app.resources.workflow_status_unknown
import run.ostrom.app.resources.workflow_status_waiting
import run.ostrom.app.resources.workflows_filter_all
import run.ostrom.app.resources.workflows_filter_done
import run.ostrom.app.resources.workflows_filter_failed
import run.ostrom.app.resources.workflows_filter_running
import run.ostrom.app.resources.workflows_filter_waiting

@Composable
internal fun runStatusWords(status: RunStatus): String = stringResource(when (status) {
    RunStatus.QUEUED -> Res.string.workflow_status_queued
    RunStatus.RUNNING -> Res.string.workflow_status_running
    RunStatus.WAITING -> Res.string.workflow_status_waiting
    RunStatus.SUCCEEDED -> Res.string.workflow_status_succeeded
    RunStatus.FAILED -> Res.string.workflow_status_failed
    RunStatus.CANCELLED -> Res.string.workflow_status_cancelled
    RunStatus.UNKNOWN -> Res.string.workflow_status_unknown
})

internal fun runStatusTone(status: RunStatus): Tone = when (status) {
    RunStatus.RUNNING, RunStatus.QUEUED -> Tone.ACCENT
    RunStatus.WAITING -> Tone.WARN
    RunStatus.FAILED -> Tone.DANGER
    RunStatus.SUCCEEDED -> Tone.NEUTRAL
    RunStatus.CANCELLED, RunStatus.UNKNOWN -> Tone.MUTED
}

@Composable
internal fun nodeKindWords(kind: NodeKind): String = stringResource(when (kind) {
    NodeKind.TRIGGER -> Res.string.workflow_node_trigger
    NodeKind.STEP -> Res.string.workflow_node_step
    NodeKind.CHECK -> Res.string.workflow_node_check
    NodeKind.GATE -> Res.string.workflow_node_gate
    NodeKind.NOTIFY -> Res.string.workflow_node_notify
    NodeKind.AGENT -> Res.string.workflow_node_agent
    NodeKind.WAIT -> Res.string.workflow_node_wait
    NodeKind.WORKFLOW -> Res.string.workflow_node_workflow
    NodeKind.UNKNOWN -> Res.string.workflow_node_unknown
})

/** "45 s", "12 min", "3 h 05": how long a run ran. */
@Composable
internal fun durationWords(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> stringResource(Res.string.workflow_duration_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(Res.string.workflow_duration_minutes, (seconds / 60).toInt())
        else -> stringResource(Res.string.workflow_duration_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toString().padStart(2, '0'))
    }
}

/** The status chips of a runs list. */
@Composable
internal fun RunFilters(selected: RunFilter, onSelect: (RunFilter) -> Unit, modifier: Modifier = Modifier) {
    Segmented(
        listOf(
            RunFilter.ALL to stringResource(Res.string.workflows_filter_all), RunFilter.RUNNING to stringResource(Res.string.workflows_filter_running),
            RunFilter.WAITING to stringResource(Res.string.workflows_filter_waiting), RunFilter.DONE to stringResource(Res.string.workflows_filter_done),
            RunFilter.FAILED to stringResource(Res.string.workflows_filter_failed),
        ),
        selected, onSelect, modifier, tag = { "runs-filter-${it.name.lowercase()}" },
    )
}

/** A run in a list: its workflow and status, the node in progress, when it started and how long it ran. */
@Composable
internal fun RunCard(run: WorkflowRun, now: Long, onOpen: () -> Unit) {
    val colors = Ostrom.colors
    val today = localEpochDay(now)
    AppCard(Modifier.testTag("workflow-run-${run.id}"), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(run.workflowName, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(runStatusWords(run.status), tone = runStatusTone(run.status), dot = run.status.active, modifier = Modifier.testTag("workflow-run-status-${run.id}"))
        }
        run.currentTitle?.let { step ->
            Text(stringResource(Res.string.workflow_run_step, step, run.currentNodeKind?.let { nodeKindWords(it) } ?: ""), color = colors.text, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("workflow-run-step-${run.id}"))
        }
        RunTimes(run, now, today, Modifier.testTag("workflow-run-time-${run.id}"))
        if (run.status == RunStatus.FAILED) (run.failure ?: run.failureCode)?.let {
            Text(it, color = colors.danger, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("workflow-run-failure-${run.id}"))
        }
        Text(listOf(run.startedBy, run.source).joinToString(" · "), style = MetaStyle, color = colors.muted)
    }
}

/** "Started 09:00 · running for 12 min", or "Ended 09:12 after 12 min". */
@Composable
internal fun RunTimes(run: WorkflowRun, now: Long, today: Long, modifier: Modifier = Modifier) {
    val colors = Ostrom.colors
    val started = run.startedAt ?: return
    val duration = run.durationMillis(now)?.let { durationWords(it) } ?: ""
    val ended = run.endedAt
    Text(
        if (ended != null) stringResource(Res.string.workflow_run_ended, formatEventTime(ended, today), duration)
        else stringResource(Res.string.workflow_run_started, formatEventTime(started, today)) + " · " + stringResource(Res.string.workflow_run_running_for, duration),
        style = MetaStyle, color = colors.muted, modifier = modifier,
    )
}
