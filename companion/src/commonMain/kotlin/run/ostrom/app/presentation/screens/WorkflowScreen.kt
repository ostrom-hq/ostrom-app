// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowNode
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
import run.ostrom.app.presentation.viewmodels.WorkflowRunsViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.workflow_input_default
import run.ostrom.app.resources.workflow_input_required
import run.ostrom.app.resources.workflow_inputs
import run.ostrom.app.resources.workflow_nodes
import run.ostrom.app.resources.workflow_refresh
import run.ostrom.app.resources.workflow_repos
import run.ostrom.app.resources.workflow_runs
import run.ostrom.app.resources.workflow_summary
import run.ostrom.app.resources.workflow_title
import run.ostrom.app.resources.workflows_edit_note
import run.ostrom.app.resources.workflows_title

/**
 * One saved workflow, read only: its status, version and repositories, its inputs, a summary of its nodes, then its
 * runs (newest first, filtered by status, kept live by the app's one feed while shown). [onOpenRun] pushes a run.
 */
@Composable
fun WorkflowScreen(
    viewModel: WorkflowViewModel,
    runs: WorkflowRunsViewModel,
    onBack: (() -> Unit)?,
    onOpenRun: (runId: String) -> Unit,
    backLabel: String = stringResource(Res.string.workflows_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val runsState by runs.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(runs) {
        runs.start()
        onStopOrDispose { runs.stop() }
    }
    val now = rememberNow()
    val colors = Ostrom.colors
    val workflow = state.workflow
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, workflow?.name ?: stringResource(Res.string.workflow_title)) {
            AppIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.workflow_refresh), onClick = { viewModel.load(); runs.reload() },
                enabled = !state.loading, modifier = Modifier.testTag("workflow-refresh"))
        }
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("workflow-page"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.error?.let { error ->
                        Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-error"), action = if (!state.gone) {
                            { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("workflow-retry")) }
                        } else null)
                    }
                    BusyRow(state.loading && workflow == null)
                }
            }
            if (workflow != null) {
                item(key = "overview") { Overview(workflow) }
                if (workflow.inputs.isNotEmpty()) {
                    item(key = "inputs-title") { SectionTitle(stringResource(Res.string.workflow_inputs)) }
                    item(key = "inputs") {
                        AppCard(Modifier.testTag("workflow-inputs")) {
                            workflow.inputs.forEach { input ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(input.label, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    if (input.required) Chip(stringResource(Res.string.workflow_input_required), tone = Tone.ACCENT)
                                }
                                input.default?.takeIf { it.isNotBlank() }?.let { Text(stringResource(Res.string.workflow_input_default, it), style = MetaStyle, color = colors.muted) }
                            }
                        }
                    }
                }
                item(key = "nodes-title") { SectionTitle(stringResource(Res.string.workflow_nodes)) }
                items(workflow.nodes, key = { "node-${it.id}" }) { NodeRow(it) }
                item(key = "runs-title") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(stringResource(Res.string.workflow_runs))
                        RunFilters(runsState.filter, runs::setFilter, Modifier.fillMaxWidth())
                        runsState.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("workflow-runs-error")) }
                    }
                }
                runItems(runsState, now, runs::loadMore, onOpenRun)
            }
        }
    }
}

@Composable
private fun Overview(workflow: Workflow) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("workflow-overview")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(workflow.name, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(workflow.status)
        }
        workflow.description?.let { Text(it, color = colors.text, fontSize = 14.sp) }
        Text(stringResource(Res.string.workflow_summary, workflow.version ?: 1, workflow.nodes.size, workflow.domain), style = MetaStyle, color = colors.muted)
        if (workflow.repos.isNotEmpty()) Text(stringResource(Res.string.workflow_repos, workflow.repos.joinToString(", ")), style = MetaStyle, color = colors.muted)
        Text(stringResource(Res.string.workflows_edit_note), fontSize = 12.sp, color = colors.muted)
    }
}

@Composable
private fun NodeRow(node: WorkflowNode) {
    val colors = Ostrom.colors
    AppCard(Modifier.testTag("workflow-node-${node.id}"), padding = 12.dp, spacing = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(node.label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(nodeKindWords(node.kind))
        }
        node.detail?.let { Text(it, style = MonospaceStyle, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}
