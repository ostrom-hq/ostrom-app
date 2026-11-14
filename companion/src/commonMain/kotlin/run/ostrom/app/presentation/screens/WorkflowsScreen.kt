// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowsScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.adamglin.phosphoricons.regular.FlowArrow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.EmptyState
import run.ostrom.app.presentation.components.KeepAtTopOnNewFirstRow
import run.ostrom.app.presentation.components.LargeTitle
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.rememberNow
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.WorkflowRunsUiState
import run.ostrom.app.presentation.viewmodels.WorkflowRunsViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowsViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_load_more
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.workflow_summary
import run.ostrom.app.resources.workflows_edit_note
import run.ostrom.app.resources.workflows_empty
import run.ostrom.app.resources.workflows_empty_body
import run.ostrom.app.resources.workflows_part_definitions
import run.ostrom.app.resources.workflows_part_runs
import run.ostrom.app.resources.workflows_reconnecting
import run.ostrom.app.resources.workflows_refresh
import run.ostrom.app.resources.workflows_runs_empty
import run.ostrom.app.resources.workflows_runs_empty_body
import run.ostrom.app.resources.workflows_title

private enum class WorkflowsPart { RUNS, DEFINITIONS }

/**
 * The Workflows tab: the member's runs, newest first, with the status filter, kept live by the app's one feed; and the
 * saved workflows, read only (the console edits them and starts runs). [onOpenRun] and [onOpenWorkflow] push a run or a
 * workflow.
 */
@Composable
fun WorkflowsScreen(
    onOpenRun: (accountId: String, runId: String) -> Unit,
    onOpenWorkflow: (accountId: String, workflowId: String) -> Unit,
    runs: WorkflowRunsViewModel = koinViewModel(),
    definitions: WorkflowsViewModel = koinViewModel(),
) {
    val colors = Ostrom.colors
    var part by rememberSaveable { mutableStateOf(WorkflowsPart.RUNS) }
    val runsState by runs.uiState.collectAsStateWithLifecycle()
    val definitionsState by definitions.uiState.collectAsStateWithLifecycle()
    // The runs follow the feed while the tab shows; the definitions have no event and are read at each return.
    LifecycleStartEffect(runs) {
        runs.start()
        onStopOrDispose { runs.stop() }
    }
    LifecycleStartEffect(definitions) {
        definitions.start()
        onStopOrDispose { }
    }
    val now = rememberNow()
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle(stringResource(Res.string.workflows_title)) {
                AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.workflows_refresh),
                    onClick = { if (part == WorkflowsPart.RUNS) runs.reload() else definitions.reload() }, modifier = Modifier.testTag("workflows-refresh"))
            }
            Segmented(
                listOf(WorkflowsPart.RUNS to stringResource(Res.string.workflows_part_runs), WorkflowsPart.DEFINITIONS to stringResource(Res.string.workflows_part_definitions)),
                part, { part = it }, Modifier.fillMaxWidth(), tag = { "workflows-part-${it.name.lowercase()}" },
            )
            if (part == WorkflowsPart.RUNS) {
                RunFilters(runsState.filter, runs::setFilter, Modifier.fillMaxWidth())
                if (runsState.reconnecting) Banner(stringResource(Res.string.workflows_reconnecting), Tone.WARN, Modifier.testTag("workflows-reconnecting"))
                runsState.error?.let { error ->
                    Banner(error.words(), Tone.DANGER, Modifier.testTag("workflows-error"), action = {
                        AppButton(stringResource(Res.string.action_retry), onClick = runs::reload, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("workflows-retry"))
                    })
                }
            } else {
                Text(stringResource(Res.string.workflows_edit_note), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("workflows-edit-note"))
                definitionsState.error?.let { error ->
                    Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-definitions-error"), action = {
                        AppButton(stringResource(Res.string.action_retry), onClick = definitions::reload, kind = ButtonKind.SECONDARY, small = true, fill = false)
                    })
                }
            }
        }
        val listState = rememberLazyListState()
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag(if (part == WorkflowsPart.RUNS) "workflow-runs" else "workflow-definitions"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (part == WorkflowsPart.RUNS) {
                runItems(runsState, now, runs::loadMore) { run -> runsState.accountId?.let { onOpenRun(it, run) } }
            } else {
                items(definitionsState.workflows.orEmpty(), key = { it.id }) { workflow ->
                    WorkflowCard(workflow) { definitionsState.account?.let { onOpenWorkflow(it.id, workflow.id) } }
                }
                item(key = "end") {
                    BusyRow(definitionsState.loading)
                    if (definitionsState.empty && definitionsState.error == null) {
                        EmptyState(PhosphorIcons.Regular.FlowArrow, stringResource(Res.string.workflows_empty), stringResource(Res.string.workflows_empty_body), Modifier.testTag("workflow-definitions-empty"))
                    }
                }
            }
        }
        if (part == WorkflowsPart.RUNS) {
            val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
            LaunchedEffect(nearEnd, runsState.next) { if (nearEnd && runsState.error == null) runs.loadMore() }
            KeepAtTopOnNewFirstRow(listState, runsState.runs?.firstOrNull()?.id ?: "end")
        }
    }
}

/** The rows of a runs list, its spinner, its empty state and its "Load more". */
internal fun LazyListScope.runItems(state: WorkflowRunsUiState, now: Long, onMore: () -> Unit, onOpen: (runId: String) -> Unit) {
    items(state.runs.orEmpty(), key = { it.id }) { run -> RunCard(run, now) { onOpen(run.id) } }
    item(key = "end") {
        BusyRow(state.loading || state.loadingMore)
        if (state.empty && state.error == null) {
            EmptyState(PhosphorIcons.Regular.FlowArrow, stringResource(Res.string.workflows_runs_empty), stringResource(Res.string.workflows_runs_empty_body), Modifier.testTag("workflow-runs-empty"))
        }
        if (state.next != null && !state.loadingMore && !state.loading) {
            AppButton(stringResource(Res.string.action_load_more), onClick = onMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("workflow-runs-more"))
        }
    }
}

/** A saved workflow: its name and status, what it does, its version and size. */
@Composable
private fun WorkflowCard(workflow: Workflow, onOpen: () -> Unit) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("workflow-${workflow.id}"), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(workflow.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(workflow.status, tone = if (workflow.status == "rejected") Tone.MUTED else Tone.NEUTRAL)
        }
        workflow.description?.let { Text(it, color = colors.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(stringResource(Res.string.workflow_summary, workflow.version ?: 1, workflow.nodes.size, workflow.domain), style = MetaStyle, color = colors.muted)
    }
}
