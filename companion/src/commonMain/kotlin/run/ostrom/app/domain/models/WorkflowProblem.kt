// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/workflows/WorkflowChecks.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * What is wrong with a draft, in the terms of the server's save checks (`WorkflowChecks`, `NodeChecks`, `CallChecks`).
 * The screens word each rule in the person's language; [WorkflowProblem.args] fill the sentence.
 */
enum class ProblemRule {
    NAME, NAME_TAKEN, DESCRIPTION, LIMITS, TOO_MANY_NODES, TOO_MANY_INPUTS,
    INPUT_ID, INPUT_LABEL, INPUT_MAX_LENGTH, INPUT_DEFAULT_LONG, INPUT_DUPLICATE,
    NODE_ID, NODE_DUPLICATE, START_MISSING, START_WAIT, NOT_REACHABLE, NO_ENDING,
    TITLE, MAX_VISITS, TEMPLATE_UNKNOWN,
    AGENT_INSTRUCTION, DELIVERABLE_LABEL, DELIVERABLE_DOMAIN, DELIVERABLE_SCHEMA_ONLY_JSON, DELIVERABLE_SCHEMA_INVALID,
    WAIT_QUESTION, ACTIONS_TOO_MANY, ACTION_ID, ACTION_DUPLICATE,
    CALLEE_MISSING, CALLEE_UNKNOWN, CALLEE_SELF, CALLEE_LOOP, CALLEE_DEPTH, ARGUMENT_NAME, ARGUMENT_MISSING, ARGUMENT_UNKNOWN,
    TRANSITION_DUPLICATE, TRANSITION_OUTCOME, TRANSITION_TARGET, TRANSITION_MISSING,
}

/** One refusal. [nodeId] is the node it concerns (null for the workflow as a whole); [args] fill the sentence (a name, an id, a number). */
data class WorkflowProblem(val rule: ProblemRule, val nodeId: String? = null, val args: List<String> = emptyList())
