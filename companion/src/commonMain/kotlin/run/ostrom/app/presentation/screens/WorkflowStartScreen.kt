// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowStartScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.viewmodels.WorkflowStartViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.workflow_input_max
import run.ostrom.app.resources.workflow_start_button
import run.ostrom.app.resources.workflow_start_input_optional
import run.ostrom.app.resources.workflow_start_input_required
import run.ostrom.app.resources.workflow_start_no_inputs
import run.ostrom.app.resources.workflow_start_title
import run.ostrom.app.resources.workflow_title

/**
 * The form that starts a run: a field for each input (its label, required or optional, its default, its maximum
 * length), then Start, enabled once every required input has a value, which lands on the new run's page.
 */
@Composable
fun WorkflowStartScreen(
    viewModel: WorkflowStartViewModel,
    onBack: (() -> Unit)?,
    onStarted: (WorkflowRun) -> Unit,
    backLabel: String = stringResource(Res.string.workflow_title),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    val workflow = state.workflow
    LaunchedEffect(state.started) { state.started?.let(onStarted) }
    Column(Modifier.fillMaxSize().background(colors.background).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.workflow_start_title))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                .testTag("workflow-start-form"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("workflow-start-load-error"), action = if (!state.gone) {
                    { AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false) }
                } else null)
            }
            BusyRow(state.loading && workflow == null)
            if (workflow != null) {
                Text(workflow.name, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (workflow.inputs.isEmpty()) Text(stringResource(Res.string.workflow_start_no_inputs), color = colors.muted, fontSize = 14.sp)
                workflow.inputs.forEach { input ->
                    val label = stringResource(if (input.required) Res.string.workflow_start_input_required else Res.string.workflow_start_input_optional, input.label)
                    val long = input.maxLength > LONG_INPUT
                    AppTextField(
                        state.valueOf(input.id), { viewModel.onInput(input.id, it) }, Modifier.fillMaxWidth(),
                        label = label, hint = stringResource(Res.string.workflow_input_max, input.maxLength), singleLine = !long,
                        minLines = if (long) 3 else 1, tag = "workflow-input-${input.id}",
                    )
                }
                state.startError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("workflow-start-error")) }
                AppButton(stringResource(Res.string.workflow_start_button), onClick = viewModel::start, enabled = state.canStart, busy = state.starting,
                    modifier = Modifier.testTag("workflow-start-submit"))
            }
        }
    }
}

/** Above this many characters, an input is a multi-line field. */
private const val LONG_INPUT = 200
