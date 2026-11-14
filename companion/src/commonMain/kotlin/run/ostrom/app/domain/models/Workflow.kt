// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/Workflow.kt; see NOTICE.
package run.ostrom.app.domain.models

/** The state of a run. [UNKNOWN] is a status a newer server added: shown as text, never as finished. */
enum class RunStatus(val wire: String) {
    QUEUED("queued"), RUNNING("running"), WAITING("waiting"), SUCCEEDED("succeeded"), FAILED("failed"), CANCELLED("cancelled"), UNKNOWN("");

    /** Still moving. */
    val active: Boolean get() = this == QUEUED || this == RUNNING || this == WAITING
}

/** What a node of a workflow is. [UNKNOWN] is a kind a newer server added. */
enum class NodeKind { TRIGGER, STEP, CHECK, GATE, NOTIFY, AGENT, WAIT, WORKFLOW, UNKNOWN }

/** The chips of the runs list; the server filters on one status, so "Done" is the succeeded ones. [ALL] shows every run. */
enum class RunFilter(val status: String?) { ALL(null), RUNNING("running"), WAITING("waiting"), DONE("succeeded"), FAILED("failed") }

/**
 * One pass through a node of a run. [title] is the node's title in the definition the run started with, else its id.
 * [status] is the server's word (`active`, `done`, `failed`...). [sessionId], [waitId], [gateId] and [childRunId] point
 * at the session an agent worked in, the wait a person answers, the gate asked, and the run of a sub-workflow.
 */
data class RunVisit(
    val seq: Int,
    val nodeId: String,
    val title: String,
    val kind: NodeKind,
    val status: String,
    val outcome: String?,
    val output: String?,
    val sessionId: String?,
    val waitId: String?,
    val gateId: String?,
    val childRunId: String?,
    val startedAt: Long?,
    val endedAt: Long?,
) {
    val active: Boolean get() = endedAt == null && status == "active"
}

/**
 * A run of a workflow, on the version it started with. In a list [visits] may be empty and [currentNodeId] names the
 * node in progress; on a single read [visits] holds every pass. Times in epoch milliseconds, null when unreadable.
 */
data class WorkflowRun(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val workflowVersion: Int,
    val status: RunStatus,
    val startedBy: String,
    /** How it started: `manual`, `automation`, `api`... */
    val source: String,
    val inputs: Map<String, String>,
    val currentNodeId: String?,
    val currentNodeKind: NodeKind?,
    val parentRunId: String?,
    val sessionId: String?,
    val repository: String?,
    val visits: List<RunVisit>,
    /** Why it failed, as the server words it, and its short code (`session_failed`, `max_visits`...). */
    val failure: String?,
    val failureCode: String?,
    val output: String?,
    val receiptId: String?,
    val startedAt: Long?,
    val endedAt: Long?,
) {
    /** The node in progress: the active visit's title on a single read, else the node's id. */
    val currentTitle: String? get() = visits.lastOrNull { it.active }?.title ?: currentNodeId?.takeIf { status.active }

    /** How long it ran (until it ended, else until [now]); null without a readable start. */
    fun durationMillis(now: Long): Long? = startedAt?.let { (endedAt ?: maxOf(now, it)) - it }
}

data class WorkflowRunPage(val runs: List<WorkflowRun>, val next: String?)

data class WorkflowInput(val id: String, val label: String, val required: Boolean, val default: String?)

/** One node of a definition, read only: its [label] and what it does ([detail]: the command, the message, the instructions...). */
data class WorkflowNode(val id: String, val kind: NodeKind, val label: String, val detail: String?)

/** A saved workflow, read only here (the console edits them). [status] is `proposed`, `accepted`, `edited` or `rejected`. */
data class Workflow(
    val id: String,
    val name: String,
    val domain: String,
    val description: String?,
    val version: Int?,
    val repos: List<String>,
    val status: String,
    val start: String?,
    val inputs: List<WorkflowInput>,
    val nodes: List<WorkflowNode>,
    val updatedAt: Long?,
) {
    fun node(id: String): WorkflowNode? = nodes.firstOrNull { it.id == id }
}
