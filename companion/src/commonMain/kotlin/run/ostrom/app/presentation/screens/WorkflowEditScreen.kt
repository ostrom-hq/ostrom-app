// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowEditScreen.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Trash
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.ProblemRule
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowProblem
import run.ostrom.app.domain.usecases.workflows.WorkflowEdits
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppCard
import run.ostrom.app.presentation.components.AppIconButton
import run.ostrom.app.presentation.components.AppTextField
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.BusyRow
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.Chip
import run.ostrom.app.presentation.components.ContentWidth
import run.ostrom.app.presentation.components.NavBar
import run.ostrom.app.presentation.components.Segmented
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.dismissKeyboardOnTap
import run.ostrom.app.presentation.theme.MetaStyle
import run.ostrom.app.presentation.theme.MonospaceStyle
import run.ostrom.app.presentation.theme.Ostrom
import run.ostrom.app.presentation.theme.Radii
import run.ostrom.app.presentation.viewmodels.HeldChange
import run.ostrom.app.presentation.viewmodels.WorkflowEditUiState
import run.ostrom.app.presentation.viewmodels.WorkflowEditViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_back
import run.ostrom.app.resources.action_retry
import run.ostrom.app.resources.wfe_action_add
import run.ostrom.app.resources.wfe_action_id
import run.ostrom.app.resources.wfe_action_label
import run.ostrom.app.resources.wfe_action_remove
import run.ostrom.app.resources.wfe_actions
import run.ostrom.app.resources.wfe_actions_none
import run.ostrom.app.resources.wfe_add_agent
import run.ostrom.app.resources.wfe_add_wait
import run.ostrom.app.resources.wfe_add_workflow
import run.ostrom.app.resources.wfe_agent
import run.ostrom.app.resources.wfe_arguments
import run.ostrom.app.resources.wfe_arguments_none
import run.ostrom.app.resources.wfe_ask
import run.ostrom.app.resources.wfe_callee
import run.ostrom.app.resources.wfe_callee_id
import run.ostrom.app.resources.wfe_callee_none
import run.ostrom.app.resources.wfe_conflict
import run.ostrom.app.resources.wfe_conflict_reload
import run.ostrom.app.resources.wfe_deliverable
import run.ostrom.app.resources.wfe_deliverable_artifact
import run.ostrom.app.resources.wfe_deliverable_domain
import run.ostrom.app.resources.wfe_deliverable_json
import run.ostrom.app.resources.wfe_deliverable_label
import run.ostrom.app.resources.wfe_deliverable_schema
import run.ostrom.app.resources.wfe_deliverable_text
import run.ostrom.app.resources.wfe_deliverable_url
import run.ostrom.app.resources.wfe_description
import run.ostrom.app.resources.wfe_domain
import run.ostrom.app.resources.wfe_end
import run.ostrom.app.resources.wfe_first
import run.ostrom.app.resources.wfe_held
import run.ostrom.app.resources.wfe_held_no_link
import run.ostrom.app.resources.wfe_held_not_opened
import run.ostrom.app.resources.wfe_held_open
import run.ostrom.app.resources.wfe_input_add
import run.ostrom.app.resources.wfe_instruction
import run.ostrom.app.resources.wfe_input_default
import run.ostrom.app.resources.wfe_input_id
import run.ostrom.app.resources.wfe_input_label
import run.ostrom.app.resources.wfe_input_max_length
import run.ostrom.app.resources.wfe_input_remove
import run.ostrom.app.resources.wfe_input_required
import run.ostrom.app.resources.wfe_inputs
import run.ostrom.app.resources.wfe_inputs_hint
import run.ostrom.app.resources.wfe_kind_fixed
import run.ostrom.app.resources.wfe_limit_depth
import run.ostrom.app.resources.wfe_limit_nodes
import run.ostrom.app.resources.wfe_limit_visits
import run.ostrom.app.resources.wfe_limits
import run.ostrom.app.resources.wfe_limits_hint
import run.ostrom.app.resources.wfe_make_first
import run.ostrom.app.resources.wfe_model
import run.ostrom.app.resources.wfe_name
import run.ostrom.app.resources.wfe_not_allowed
import run.ostrom.app.resources.wfe_outcome_delivered
import run.ostrom.app.resources.wfe_outcome_done
import run.ostrom.app.resources.wfe_outcome_next
import run.ostrom.app.resources.wfe_problems_count
import run.ostrom.app.resources.wfe_question
import run.ostrom.app.resources.wfe_save
import run.ostrom.app.resources.wfe_session_handoff
import run.ostrom.app.resources.wfe_session_mode
import run.ostrom.app.resources.wfe_session_same
import run.ostrom.app.resources.wfe_step_down
import run.ostrom.app.resources.wfe_step_id
import run.ostrom.app.resources.wfe_step_id_hint
import run.ostrom.app.resources.wfe_step_max_visits
import run.ostrom.app.resources.wfe_step_remove
import run.ostrom.app.resources.wfe_step_title
import run.ostrom.app.resources.wfe_step_up
import run.ostrom.app.resources.wfe_steps
import run.ostrom.app.resources.wfe_templates
import run.ostrom.app.resources.wfe_title_edit
import run.ostrom.app.resources.wfe_title_new
import run.ostrom.app.resources.wfe_untitled
import run.ostrom.app.resources.wfe_when

internal val flatKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

/**
 * The workflow editor. Top to bottom: the name, description, domain and session mode; the run inputs; the steps, each
 * a card that folds (title and id, then what its kind does: an agent's instructions and deliverable, a question's
 * buttons, the workflow a step calls; where each outcome goes; how often it may be visited; its place); the limits;
 * Save. Every rule the server checks is checked as the person types and shown against its step, and Save stays off
 * while any is left. Steps of the kinds the console edits (trigger, step, check, gate, notify) keep what they do and
 * are only routed here.
 */
@Composable
fun WorkflowEditScreen(viewModel: WorkflowEditViewModel, onBack: (() -> Unit)?, onSaved: (workflowId: String) -> Unit, backLabel: String = stringResource(Res.string.action_back)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Ostrom.colors
    val draft = state.draft
    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }
    val showsForm = !state.loading && state.loadError == null && !state.notAllowed
    Column(Modifier.fillMaxSize().background(colors.background).imePadding().dismissKeyboardOnTap(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(if (!state.creating) Res.string.wfe_title_edit else Res.string.wfe_title_new))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).testTag("wfe-form"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BusyRow(state.loading)
            state.loadError?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("wfe-load-error"), action = {
                    AppButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, fill = false, modifier = Modifier.testTag("wfe-load-retry"))
                })
            }
            if (state.notAllowed) Banner(stringResource(Res.string.wfe_not_allowed), Tone.WARN, Modifier.testTag("wfe-not-allowed"))
            state.held?.let { HeldNotice(it, "wfe-held") }
            if (state.conflict) Column(Modifier.testTag("wfe-conflict"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Banner(stringResource(Res.string.wfe_conflict), Tone.DANGER)
                AppButton(stringResource(Res.string.wfe_conflict_reload), onClick = viewModel::reloadAfterConflict, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("wfe-conflict-reload"))
            }
            if (showsForm) {
                AppCard(Modifier.testTag("wfe-general"), spacing = 10.dp) {
                    AppTextField(draft.name, { v -> viewModel.edit { it.copy(name = v) } }, label = stringResource(Res.string.wfe_name),
                        error = state.problemsOf(null).any { it.rule == ProblemRule.NAME || it.rule == ProblemRule.NAME_TAKEN }, tag = "wfe-name")
                    AppTextField(draft.description, { v -> viewModel.edit { it.copy(description = v) } }, label = stringResource(Res.string.wfe_description), singleLine = false, minLines = 2, tag = "wfe-description")
                    AppTextField(draft.domain, { v -> viewModel.edit { it.copy(domain = v) } }, label = stringResource(Res.string.wfe_domain), tag = "wfe-domain")
                    Text(stringResource(Res.string.wfe_session_mode), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Segmented(
                        listOf(WorkflowDraft.SAME_SESSION to stringResource(Res.string.wfe_session_same), HANDOFF to stringResource(Res.string.wfe_session_handoff)),
                        selected = draft.sessionMode ?: HANDOFF, onSelect = { m -> viewModel.edit { it.copy(sessionMode = m.takeIf { mode -> mode != HANDOFF }) } },
                        modifier = Modifier.fillMaxWidth(), tag = { "wfe-session-$it" },
                    )
                    Problems(state.problemsOf(null), Modifier.testTag("wfe-general-problems"))
                }

                FoldSection("inputs", stringResource(Res.string.wfe_inputs), count = draft.inputs.size) {
                    Text(stringResource(Res.string.wfe_inputs_hint), style = MetaStyle, color = colors.muted)
                    draft.inputs.forEachIndexed { index, input -> InputEditor(index, input, viewModel) }
                    AppButton(stringResource(Res.string.wfe_input_add), onClick = { viewModel.edit { it.copy(inputs = it.inputs + WorkflowEdits.newInput(it)) } },
                        kind = ButtonKind.SECONDARY, small = true, fill = false, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("wfe-input-add"))
                }

                Text(stringResource(Res.string.wfe_steps), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                draft.nodes.forEachIndexed { index, node -> NodeEditor(node, index, draft.nodes.size, viewModel, state) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddStep(Res.string.wfe_add_agent, NodeKind.AGENT, "wfe-add-agent", viewModel)
                    AddStep(Res.string.wfe_add_wait, NodeKind.WAIT, "wfe-add-wait", viewModel)
                    AddStep(Res.string.wfe_add_workflow, NodeKind.WORKFLOW, "wfe-add-workflow", viewModel)
                }

                FoldSection("limits", stringResource(Res.string.wfe_limits), initiallyOpen = false) {
                    Text(stringResource(Res.string.wfe_limits_hint), style = MetaStyle, color = colors.muted)
                    NumberField(draft.limits.maxNodes, { v -> viewModel.edit { it.copy(limits = it.limits.copy(maxNodes = v)) } }, stringResource(Res.string.wfe_limit_nodes), "wfe-limit-nodes")
                    NumberField(draft.limits.maxVisits, { v -> viewModel.edit { it.copy(limits = it.limits.copy(maxVisits = v)) } }, stringResource(Res.string.wfe_limit_visits), "wfe-limit-visits")
                    NumberField(draft.limits.maxDepth, { v -> viewModel.edit { it.copy(limits = it.limits.copy(maxDepth = v)) } }, stringResource(Res.string.wfe_limit_depth), "wfe-limit-depth")
                }
            }
            state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("wfe-save-error")) }
        }
        if (showsForm) Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.problems.isNotEmpty()) Text(stringResource(Res.string.wfe_problems_count, state.problems.size), style = MetaStyle, color = colors.danger, modifier = Modifier.testTag("wfe-problems-count"))
            AppButton(stringResource(Res.string.wfe_save), onClick = viewModel::save, enabled = state.canSave, busy = state.saving, modifier = Modifier.testTag("wfe-save"))
        }
    }
}

private const val HANDOFF = "handoff"

@Composable
private fun AddStep(label: org.jetbrains.compose.resources.StringResource, kind: NodeKind, tag: String, viewModel: WorkflowEditViewModel) {
    AppButton(stringResource(label), onClick = { viewModel.edit { WorkflowEdits.addNode(it, kind) } }, kind = ButtonKind.SECONDARY, small = true, fill = false,
        icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag(tag))
}

/**
 * A change held for a confirmation: what it means, and its console page when it opens on the account's own server
 * (else nothing to tap: the console lists the confirmations). A page the system cannot open is shown to copy.
 */
@Composable
internal fun HeldNotice(held: HeldChange, tag: String) {
    val colors = Ostrom.colors
    val browser = LocalUriHandler.current
    var failed by rememberSaveable(held.link) { mutableStateOf(false) }
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Banner(stringResource(Res.string.wfe_held), Tone.WARN)
        val link = held.link
        if (link == null) {
            Text(stringResource(Res.string.wfe_held_no_link), style = MetaStyle, color = colors.muted, modifier = Modifier.testTag("$tag-no-link"))
        } else {
            AppButton(stringResource(Res.string.wfe_held_open), icon = PhosphorIcons.Regular.ArrowSquareOut, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("$tag-open"), onClick = {
                failed = try {
                    browser.openUri(link)
                    false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // No browser, or a link the system refused: the address is shown to copy.
                    true
                }
            })
            if (failed) {
                Text(stringResource(Res.string.wfe_held_not_opened), color = colors.text, fontSize = 13.5.sp)
                SelectionContainer { Text(link, style = MonospaceStyle, color = colors.text, modifier = Modifier.testTag("$tag-url")) }
            }
        }
    }
}

/** A foldable group of fields: a header that opens and closes it. */
@Composable
internal fun FoldSection(id: String, title: String, initiallyOpen: Boolean = true, count: Int? = null, content: @Composable () -> Unit) {
    val colors = Ostrom.colors
    var open by rememberSaveable("wfe-section-$id") { mutableStateOf(initiallyOpen) }
    AppCard(Modifier.testTag("wfe-section-$id"), spacing = 10.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { open = !open }.testTag("wfe-section-$id-header"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            count?.let { Chip(it.toString(), tone = Tone.MUTED) }
            Icon(if (open) PhosphorIcons.Regular.CaretDown else PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** A number typed in digits; empty is null (the server's default). */
@Composable
internal fun NumberField(value: Int?, onChange: (Int?) -> Unit, label: String, tag: String, modifier: Modifier = Modifier, error: Boolean = false) {
    AppTextField(
        value?.toString().orEmpty(), { text -> onChange(text.filter(Char::isDigit).take(6).toIntOrNull()) },
        modifier, label = label, error = error, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), tag = tag,
    )
}

@Composable
private fun InputEditor(index: Int, input: WorkflowInput, viewModel: WorkflowEditViewModel) {
    val colors = Ostrom.colors
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bad = state.problemsOf(null).any { it.rule in INPUT_RULES && it.args.firstOrNull() == input.id.ifBlank { "?" } }
    fun change(new: WorkflowInput) = viewModel.edit { it.copy(inputs = it.inputs.mapIndexed { i, old -> if (i == index) new else old }) }
    Column(Modifier.fillMaxWidth().border(1.dp, if (bad) colors.danger else colors.line, Radii.control).padding(10.dp).testTag("wfe-input-$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(input.id, { v -> viewModel.edit { WorkflowEdits.renameInput(it, input.id, v) } }, Modifier.weight(1f), placeholder = stringResource(Res.string.wfe_input_id), mono = true,
                keyboardOptions = flatKeyboard, tag = "wfe-input-id-$index")
            AppIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_input_remove), onClick = { viewModel.edit { it.copy(inputs = it.inputs.filterIndexed { i, _ -> i != index }) } },
                modifier = Modifier.testTag("wfe-input-remove-$index"))
        }
        AppTextField(input.label, { v -> change(input.copy(label = v)) }, placeholder = stringResource(Res.string.wfe_input_label), tag = "wfe-input-label-$index")
        AppTextField(input.default.orEmpty(), { v -> change(input.copy(default = v.ifEmpty { null })) }, placeholder = stringResource(Res.string.wfe_input_default), tag = "wfe-input-default-$index")
        NumberField(input.maxLength, { v -> change(input.copy(maxLength = v ?: 0)) }, stringResource(Res.string.wfe_input_max_length), "wfe-input-max-$index")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.wfe_input_required), color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(input.required, { r -> change(input.copy(required = r)) }, modifier = Modifier.testTag("wfe-input-required-$index"))
        }
    }
}

private val INPUT_RULES = setOf(ProblemRule.INPUT_ID, ProblemRule.INPUT_LABEL, ProblemRule.INPUT_MAX_LENGTH, ProblemRule.INPUT_DEFAULT_LONG, ProblemRule.INPUT_DUPLICATE)

/** One step as a card that folds. Its tags are numbered by position (`wfe-n0-...`), which does not change while the id is typed. */
@Composable
private fun NodeEditor(node: DraftNode, index: Int, total: Int, viewModel: WorkflowEditViewModel, state: WorkflowEditUiState) {
    val colors = Ostrom.colors
    val draft = state.draft
    // A step just added (nothing typed) opens; so does the only step.
    var open by rememberSaveable("wfe-node-$index") { mutableStateOf(total == 1 || (node.title.isBlank() && node.instruction.isBlank())) }
    val problems = state.problemsOf(node.id)
    val tag = "wfe-n$index"
    fun change(new: (DraftNode) -> DraftNode) = viewModel.editNode(node.id, new)
    AppCard(Modifier.testTag(tag), spacing = 10.dp, borderColor = if (problems.isEmpty()) colors.line else colors.danger) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { open = !open }.testTag("$tag-header"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(node.title.ifBlank { stringResource(Res.string.wfe_untitled) }, color = if (node.title.isBlank()) colors.muted else colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (node.id == draft.first) Chip(stringResource(Res.string.wfe_first), tone = Tone.ACCENT)
            Chip(nodeKindWords(node.kind), tone = Tone.MUTED)
            if (problems.isNotEmpty()) Chip(problems.size.toString(), tone = Tone.DANGER)
            Icon(if (open) PhosphorIcons.Regular.CaretDown else PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
        Problems(problems, Modifier.testTag("$tag-problems"))
        if (!open) return@AppCard
        if (node.editable) {
            Segmented(
                DraftNode.EDITED_KINDS.map { it to nodeKindWords(it) },
                selected = node.kind, onSelect = { kind -> viewModel.edit { WorkflowEdits.setKind(it, node.id, kind) } }, modifier = Modifier.fillMaxWidth(), tag = { "$tag-kind-${it.name.lowercase()}" },
            )
        } else {
            Text(stringResource(Res.string.wfe_kind_fixed, nodeKindWords(node.kind)), style = MetaStyle, color = colors.muted)
        }
        AppTextField(node.title, { v -> change { it.copy(title = v) } }, label = stringResource(Res.string.wfe_step_title), error = problems.any { it.rule == ProblemRule.TITLE }, tag = "$tag-title")
        AppTextField(node.id, { v -> viewModel.edit { WorkflowEdits.renameNode(it, node.id, v) } }, label = stringResource(Res.string.wfe_step_id), hint = stringResource(Res.string.wfe_step_id_hint), mono = true,
            keyboardOptions = flatKeyboard, error = problems.any { it.rule == ProblemRule.NODE_ID }, tag = "$tag-id")
        val hints = WorkflowEdits.templates(draft, node.id)
        when (node.kind) {
            NodeKind.AGENT -> AgentBlock(node, tag, hints, ::change, problems)
            NodeKind.WAIT -> WaitBlock(node, tag, hints, ::change, viewModel, problems)
            NodeKind.WORKFLOW -> WorkflowBlock(node, tag, hints, ::change, state)
            else -> Unit
        }
        TransitionsBlock(node, tag, viewModel, draft)
        NumberField(node.maxVisits, { v -> change { it.copy(maxVisits = v) } }, stringResource(Res.string.wfe_step_max_visits), "$tag-max-visits", error = problems.any { it.rule == ProblemRule.MAX_VISITS })
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (node.id != draft.first && node.kind != NodeKind.WAIT) {
                AppButton(stringResource(Res.string.wfe_make_first), onClick = { viewModel.edit { it.copy(start = node.id) } }, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.weight(1f).testTag("$tag-first"))
            } else {
                Text("", Modifier.weight(1f))
            }
            AppIconButton(PhosphorIcons.Regular.CaretUp, stringResource(Res.string.wfe_step_up), onClick = { viewModel.edit { WorkflowEdits.moveNode(it, node.id, -1) } }, enabled = index > 0, modifier = Modifier.testTag("$tag-up"))
            AppIconButton(PhosphorIcons.Regular.CaretDown, stringResource(Res.string.wfe_step_down), onClick = { viewModel.edit { WorkflowEdits.moveNode(it, node.id, 1) } }, enabled = index < total - 1,
                modifier = Modifier.testTag("$tag-down"))
            AppIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_step_remove), onClick = { viewModel.edit { WorkflowEdits.removeNode(it, node.id) } }, modifier = Modifier.testTag("$tag-remove"))
        }
    }
}

@Composable
private fun AgentBlock(node: DraftNode, tag: String, hints: List<String>, change: ((DraftNode) -> DraftNode) -> Unit, problems: List<WorkflowProblem>) {
    val colors = Ostrom.colors
    TemplatedField(node.instruction, { v -> change { it.copy(instruction = v) } }, hints, stringResource(Res.string.wfe_instruction), "$tag-instruction",
        error = problems.any { it.rule == ProblemRule.AGENT_INSTRUCTION })
    Text(stringResource(Res.string.wfe_deliverable), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val deliverable = node.deliverable ?: DraftDeliverable("text")
    Segmented(
        listOf("artifact" to stringResource(Res.string.wfe_deliverable_artifact), "url" to stringResource(Res.string.wfe_deliverable_url), "text" to stringResource(Res.string.wfe_deliverable_text),
            "json" to stringResource(Res.string.wfe_deliverable_json)),
        selected = deliverable.type,
        onSelect = { type -> change { it.copy(deliverable = DraftDeliverable(type, deliverable.label, deliverable.domain.takeIf { type == "url" }, deliverable.schema.takeIf { type == "json" })) } },
        modifier = Modifier.fillMaxWidth(), tag = { "$tag-deliverable-$it" },
    )
    AppTextField(deliverable.label.orEmpty(), { v -> change { it.copy(deliverable = deliverable.copy(label = v.ifEmpty { null })) } }, label = stringResource(Res.string.wfe_deliverable_label),
        error = problems.any { it.rule == ProblemRule.DELIVERABLE_LABEL }, tag = "$tag-deliverable-label")
    if (deliverable.type == "url") {
        AppTextField(deliverable.domain.orEmpty(), { v -> change { it.copy(deliverable = deliverable.copy(domain = v.ifEmpty { null })) } }, label = stringResource(Res.string.wfe_deliverable_domain),
            hint = "github.com", mono = true, keyboardOptions = flatKeyboard, error = problems.any { it.rule == ProblemRule.DELIVERABLE_DOMAIN }, tag = "$tag-domain")
    }
    if (deliverable.type == "json") {
        AppTextField(deliverable.schema.orEmpty(), { v -> change { it.copy(deliverable = deliverable.copy(schema = v.ifEmpty { null })) } }, label = stringResource(Res.string.wfe_deliverable_schema),
            mono = true, singleLine = false, minLines = 4, keyboardOptions = flatKeyboard, error = problems.any { it.rule == ProblemRule.DELIVERABLE_SCHEMA_INVALID }, tag = "$tag-schema")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(node.agent.orEmpty(), { v -> change { it.copy(agent = v.ifEmpty { null }) } }, Modifier.weight(1f), label = stringResource(Res.string.wfe_agent), mono = true, keyboardOptions = flatKeyboard,
            tag = "$tag-agent")
        AppTextField(node.model.orEmpty(), { v -> change { it.copy(model = v.ifEmpty { null }) } }, Modifier.weight(1f), label = stringResource(Res.string.wfe_model), mono = true, keyboardOptions = flatKeyboard,
            tag = "$tag-model")
    }
}

@Composable
private fun WaitBlock(node: DraftNode, tag: String, hints: List<String>, change: ((DraftNode) -> DraftNode) -> Unit, viewModel: WorkflowEditViewModel, problems: List<WorkflowProblem>) {
    val colors = Ostrom.colors
    AppTextField(node.question, { v -> change { it.copy(question = v) } }, label = stringResource(Res.string.wfe_question), error = problems.any { it.rule == ProblemRule.WAIT_QUESTION }, tag = "$tag-question")
    TemplatedField(node.instruction, { v -> change { it.copy(instruction = v) } }, hints, stringResource(Res.string.wfe_ask), "$tag-instruction")
    Text(stringResource(Res.string.wfe_actions), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (node.actions.isEmpty()) Text(stringResource(Res.string.wfe_actions_none), style = MetaStyle, color = colors.muted)
    node.actions.forEachIndexed { j, action ->
        val at = "$tag-a$j"
        Column(Modifier.fillMaxWidth().border(1.dp, colors.line, Radii.control).padding(10.dp).testTag(at), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(action.id, { v -> viewModel.edit { WorkflowEdits.renameAction(it, node.id, action.id, v) } }, Modifier.weight(1f), placeholder = stringResource(Res.string.wfe_action_id), mono = true,
                    keyboardOptions = flatKeyboard, tag = "$at-id")
                AppIconButton(PhosphorIcons.Regular.Trash, stringResource(Res.string.wfe_action_remove), onClick = { viewModel.edit { WorkflowEdits.removeAction(it, node.id, action.id) } },
                    modifier = Modifier.testTag("$at-remove"))
            }
            AppTextField(action.label, { v -> change { it.copy(actions = it.actions.mapIndexed { i, old -> if (i == j) old.copy(label = v) else old }) } }, placeholder = stringResource(Res.string.wfe_action_label),
                tag = "$at-label")
        }
    }
    AppButton(stringResource(Res.string.wfe_action_add), onClick = { viewModel.edit { WorkflowEdits.addAction(it, node.id) } }, kind = ButtonKind.SECONDARY, small = true, fill = false,
        icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("$tag-action-add"))
}

@Composable
private fun WorkflowBlock(node: DraftNode, tag: String, hints: List<String>, change: ((DraftNode) -> DraftNode) -> Unit, state: WorkflowEditUiState) {
    val colors = Ostrom.colors
    val callees = state.callees
    Text(stringResource(Res.string.wfe_callee), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (callees != null) {
        if (callees.isEmpty()) Text(stringResource(Res.string.wfe_callee_none), color = colors.muted, fontSize = 13.sp)
        Chips { callees.forEach { w -> ChoiceChip(w.name, node.workflowId == w.id, { change { it.copy(workflowId = w.id, arguments = emptyMap()) } }, "$tag-callee-${w.id}") } }
    } else {
        AppTextField(node.workflowId.orEmpty(), { v -> change { it.copy(workflowId = v.trim().ifEmpty { null }) } }, label = stringResource(Res.string.wfe_callee_id), mono = true,
            keyboardOptions = flatKeyboard, tag = "$tag-callee-id")
    }
    val callee = node.workflowId?.let { id -> callees?.firstOrNull { it.id == id } } ?: return
    Text(stringResource(Res.string.wfe_arguments), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (callee.inputs.isEmpty()) Text(stringResource(Res.string.wfe_arguments_none), color = colors.muted, fontSize = 13.sp)
    callee.inputs.forEach { input ->
        val required = input.required && input.default == null
        TemplatedField(
            node.arguments[input.id].orEmpty(), { v -> change { it.copy(arguments = if (v.isBlank()) it.arguments - input.id else it.arguments + (input.id to v)) } }, hints,
            input.label + if (required) " *" else "", "$tag-arg-${input.id}", minLines = 1, error = required && node.arguments[input.id].isNullOrBlank(),
        )
    }
}

/** Where each outcome of the step goes: a step, or the end of the workflow. An outcome not routed yet shows no choice made. */
@Composable
private fun TransitionsBlock(node: DraftNode, tag: String, viewModel: WorkflowEditViewModel, draft: WorkflowDraft) {
    val colors = Ostrom.colors
    node.outcomes.forEach { outcome ->
        val edge = draft.edgeOf(node.id, outcome)
        // A node without any edge ends the run; a check's branches always need theirs.
        val ends = edge?.let { it.to == null } ?: (draft.edgesFrom(node.id).isEmpty() && !node.branches)
        Column(Modifier.testTag("$tag-out-$outcome"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.wfe_when, outcomeWords(node, outcome)), color = if (edge == null && !ends) colors.danger else colors.muted, fontSize = 13.sp)
            Chips {
                ChoiceChip(stringResource(Res.string.wfe_end), ends, { viewModel.edit { WorkflowEdits.route(it, node.id, outcome, null) } }, "$tag-to-$outcome-end")
                draft.nodes.forEachIndexed { k, target ->
                    ChoiceChip(target.title.ifBlank { target.id }, edge?.to == target.id, { viewModel.edit { WorkflowEdits.route(it, node.id, outcome, target.id) } }, "$tag-to-$outcome-n$k")
                }
            }
        }
    }
}

/** An outcome in words: `delivered`, `next`, `done`, or the label of the button it is the id of. */
@Composable
private fun outcomeWords(node: DraftNode, outcome: String): String = when (outcome) {
    DraftNode.DELIVERED -> stringResource(Res.string.wfe_outcome_delivered)
    DraftNode.NEXT -> stringResource(Res.string.wfe_outcome_next)
    DraftNode.DONE -> stringResource(Res.string.wfe_outcome_done)
    else -> node.actions.firstOrNull { it.id == outcome }?.label?.takeIf { it.isNotBlank() } ?: outcome
}

/** The problems of a node or of the workflow, one line each, in the person's words. */
@Composable
internal fun Problems(problems: List<WorkflowProblem>, modifier: Modifier = Modifier) {
    if (problems.isEmpty()) return
    val colors = Ostrom.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        problems.forEachIndexed { index, problem ->
            Text(problem.words(), color = colors.danger, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.testTag("wfe-problem-${problem.nodeId ?: "root"}-${problem.rule.name.lowercase()}-$index"))
        }
    }
}

/** A multi-line text with the templates the step may use offered under it: a tap adds one at the end. */
@Composable
private fun TemplatedField(value: String, onChange: (String) -> Unit, hints: List<String>, label: String, tag: String, minLines: Int = 3, error: Boolean = false) {
    val colors = Ostrom.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppTextField(value, onChange, label = label, singleLine = false, minLines = minLines, keyboardOptions = flatKeyboard, error = error, tag = tag)
        if (hints.isNotEmpty()) {
            Text(stringResource(Res.string.wfe_templates), style = MetaStyle, color = colors.muted)
            Chips {
                hints.forEachIndexed { k, token ->
                    AddChip(token, { onChange(value + (if (value.isEmpty() || value.endsWith(" ") || value.endsWith("\n")) "" else " ") + token) }, "$tag-hint-$k")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** One choice among several: the chosen one is outlined with the accent. */
@Composable
internal fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    val colors = Ostrom.colors
    Row(
        Modifier.heightIn(min = 36.dp).clip(Radii.control).background(if (selected) colors.panel else colors.panelAlt)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.accent else colors.line, Radii.control)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(text, color = if (selected) colors.text else colors.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A chip that adds a template to a text when tapped. */
@Composable
private fun AddChip(text: String, onClick: () -> Unit, tag: String) {
    val colors = Ostrom.colors
    Row(
        Modifier.heightIn(min = 34.dp).clip(Radii.control).background(colors.panelAlt).border(1.dp, colors.line, Radii.control).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(text, style = MonospaceStyle, color = colors.accentText, fontSize = 12.5.sp, maxLines = 1) }
}
