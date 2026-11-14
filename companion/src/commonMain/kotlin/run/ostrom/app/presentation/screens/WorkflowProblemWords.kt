// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/WorkflowProblemWords.kt; see NOTICE.
package run.ostrom.app.presentation.screens

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.ProblemRule
import run.ostrom.app.domain.models.WorkflowProblem
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.wfp_action_duplicate
import run.ostrom.app.resources.wfp_action_id
import run.ostrom.app.resources.wfp_actions_too_many
import run.ostrom.app.resources.wfp_agent_instruction
import run.ostrom.app.resources.wfp_argument_missing
import run.ostrom.app.resources.wfp_argument_name
import run.ostrom.app.resources.wfp_argument_unknown
import run.ostrom.app.resources.wfp_callee_depth
import run.ostrom.app.resources.wfp_callee_loop
import run.ostrom.app.resources.wfp_callee_missing
import run.ostrom.app.resources.wfp_callee_self
import run.ostrom.app.resources.wfp_callee_unknown
import run.ostrom.app.resources.wfp_deliverable_domain
import run.ostrom.app.resources.wfp_deliverable_label
import run.ostrom.app.resources.wfp_deliverable_schema_invalid
import run.ostrom.app.resources.wfp_deliverable_schema_only_json
import run.ostrom.app.resources.wfp_description
import run.ostrom.app.resources.wfp_input_default_long
import run.ostrom.app.resources.wfp_input_duplicate
import run.ostrom.app.resources.wfp_input_id
import run.ostrom.app.resources.wfp_input_label
import run.ostrom.app.resources.wfp_input_max_length
import run.ostrom.app.resources.wfp_limits
import run.ostrom.app.resources.wfp_max_visits
import run.ostrom.app.resources.wfp_name
import run.ostrom.app.resources.wfp_name_taken
import run.ostrom.app.resources.wfp_no_ending
import run.ostrom.app.resources.wfp_node_duplicate
import run.ostrom.app.resources.wfp_node_id
import run.ostrom.app.resources.wfp_not_reachable
import run.ostrom.app.resources.wfp_start_missing
import run.ostrom.app.resources.wfp_start_wait
import run.ostrom.app.resources.wfp_template_unknown
import run.ostrom.app.resources.wfp_title
import run.ostrom.app.resources.wfp_too_many_inputs
import run.ostrom.app.resources.wfp_too_many_nodes
import run.ostrom.app.resources.wfp_transition_duplicate
import run.ostrom.app.resources.wfp_transition_missing
import run.ostrom.app.resources.wfp_transition_outcome
import run.ostrom.app.resources.wfp_transition_target
import run.ostrom.app.resources.wfp_wait_question

/** A rule the draft breaks, in the person's words (the arguments are the names and limits [ProblemRule] carries). */
@Composable
fun WorkflowProblem.words(): String {
    val resource: StringResource = when (rule) {
        ProblemRule.NAME -> Res.string.wfp_name
        ProblemRule.NAME_TAKEN -> Res.string.wfp_name_taken
        ProblemRule.DESCRIPTION -> Res.string.wfp_description
        ProblemRule.LIMITS -> Res.string.wfp_limits
        ProblemRule.TOO_MANY_NODES -> Res.string.wfp_too_many_nodes
        ProblemRule.TOO_MANY_INPUTS -> Res.string.wfp_too_many_inputs
        ProblemRule.INPUT_ID -> Res.string.wfp_input_id
        ProblemRule.INPUT_LABEL -> Res.string.wfp_input_label
        ProblemRule.INPUT_MAX_LENGTH -> Res.string.wfp_input_max_length
        ProblemRule.INPUT_DEFAULT_LONG -> Res.string.wfp_input_default_long
        ProblemRule.INPUT_DUPLICATE -> Res.string.wfp_input_duplicate
        ProblemRule.NODE_ID -> Res.string.wfp_node_id
        ProblemRule.NODE_DUPLICATE -> Res.string.wfp_node_duplicate
        ProblemRule.START_MISSING -> Res.string.wfp_start_missing
        ProblemRule.START_WAIT -> Res.string.wfp_start_wait
        ProblemRule.NOT_REACHABLE -> Res.string.wfp_not_reachable
        ProblemRule.NO_ENDING -> Res.string.wfp_no_ending
        ProblemRule.TITLE -> Res.string.wfp_title
        ProblemRule.MAX_VISITS -> Res.string.wfp_max_visits
        ProblemRule.TEMPLATE_UNKNOWN -> Res.string.wfp_template_unknown
        ProblemRule.AGENT_INSTRUCTION -> Res.string.wfp_agent_instruction
        ProblemRule.DELIVERABLE_LABEL -> Res.string.wfp_deliverable_label
        ProblemRule.DELIVERABLE_DOMAIN -> Res.string.wfp_deliverable_domain
        ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON -> Res.string.wfp_deliverable_schema_only_json
        ProblemRule.DELIVERABLE_SCHEMA_INVALID -> Res.string.wfp_deliverable_schema_invalid
        ProblemRule.WAIT_QUESTION -> Res.string.wfp_wait_question
        ProblemRule.ACTIONS_TOO_MANY -> Res.string.wfp_actions_too_many
        ProblemRule.ACTION_ID -> Res.string.wfp_action_id
        ProblemRule.ACTION_DUPLICATE -> Res.string.wfp_action_duplicate
        ProblemRule.CALLEE_MISSING -> Res.string.wfp_callee_missing
        ProblemRule.CALLEE_UNKNOWN -> Res.string.wfp_callee_unknown
        ProblemRule.CALLEE_SELF -> Res.string.wfp_callee_self
        ProblemRule.CALLEE_LOOP -> Res.string.wfp_callee_loop
        ProblemRule.CALLEE_DEPTH -> Res.string.wfp_callee_depth
        ProblemRule.ARGUMENT_NAME -> Res.string.wfp_argument_name
        ProblemRule.ARGUMENT_MISSING -> Res.string.wfp_argument_missing
        ProblemRule.ARGUMENT_UNKNOWN -> Res.string.wfp_argument_unknown
        ProblemRule.TRANSITION_DUPLICATE -> Res.string.wfp_transition_duplicate
        ProblemRule.TRANSITION_OUTCOME -> Res.string.wfp_transition_outcome
        ProblemRule.TRANSITION_TARGET -> Res.string.wfp_transition_target
        ProblemRule.TRANSITION_MISSING -> Res.string.wfp_transition_missing
    }
    return stringResource(resource, *args.toTypedArray())
}
