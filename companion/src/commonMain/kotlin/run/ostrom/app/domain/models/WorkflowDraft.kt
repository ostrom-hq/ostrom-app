// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/WorkflowDefinition.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * What an agent node delivers for the run to move on: `artifact`, `url`, `text` or `json`. [domain] only for a `url`,
 * [schema] (the JSON schema as text) only for a `json`.
 */
data class DraftDeliverable(val type: String, val label: String? = null, val domain: String? = null, val schema: String? = null)

/** A button of a wait node; its [id] is the outcome the node ends with. [style] is `primary`, `danger` or null. */
data class DraftAction(val id: String, val label: String, val style: String? = null)

/**
 * One node as the editor changes it. Texts are plain strings (an empty one is "not set"). [instruction] is an agent's
 * instructions or a wait's body; [question] a wait's title. [originId] is the id the node was read with (null for a
 * node added here): a save keeps what this editor does not change (a gate's approvers, a wait's fields, a position)
 * from the node of that id. [branches] marks a check node with a condition, whose outcomes are `true` and `false`.
 */
data class DraftNode(
    val id: String,
    val kind: NodeKind,
    val title: String,
    val instruction: String = "",
    val question: String = "",
    val agent: String? = null,
    val model: String? = null,
    val deliverable: DraftDeliverable? = null,
    val actions: List<DraftAction> = emptyList(),
    val workflowId: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val maxVisits: Int? = null,
    val branches: Boolean = false,
    val originId: String? = null,
) {
    /** What the node can end with, as the server's engine plays it (`next` for the nodes it does not play itself). */
    val outcomes: List<String>
        get() = when (kind) {
            NodeKind.AGENT, NodeKind.WORKFLOW -> listOf(DELIVERED)
            NodeKind.WAIT -> actions.map { it.id }.ifEmpty { listOf(DONE) }
            NodeKind.CHECK -> if (branches) listOf("true", "false") else listOf(NEXT)
            else -> listOf(NEXT)
        }

    /** Whether the editor edits the node's content (TeamCode's three kinds); the others keep theirs and are only routed. */
    val editable: Boolean get() = kind in EDITED_KINDS

    companion object {
        const val NEXT = "next"
        const val DELIVERED = "delivered"
        const val DONE = "done"

        /** The kinds TeamCode's editor offers, which Ostrom's server plays. */
        val EDITED_KINDS: List<NodeKind> = listOf(NodeKind.AGENT, NodeKind.WAIT, NodeKind.WORKFLOW)
    }
}

/** An edge: from a node, on an outcome ([on] null is the default `next`), to a node or to the end ([to] null). */
data class DraftEdge(val from: String, val to: String?, val on: String? = null, val label: String? = null) {
    val outcome: String get() = on ?: DraftNode.NEXT
}

/** The bounds of a run; null is the server's default. */
data class DraftLimits(val maxNodes: Int? = null, val maxVisits: Int? = null, val maxDepth: Int? = null)

/**
 * The whole definition of a workflow, as the editor changes it (the read model [Workflow] keeps only what the pages
 * show). [sessionMode] is `same` (one session for the run's agent steps) or null (each starts its own, handed off).
 */
data class WorkflowDraft(
    val name: String,
    val description: String = "",
    val domain: String = "",
    val start: String? = null,
    val sessionMode: String? = SAME_SESSION,
    val inputs: List<WorkflowInput> = emptyList(),
    val nodes: List<DraftNode> = emptyList(),
    val edges: List<DraftEdge> = emptyList(),
    val limits: DraftLimits = DraftLimits(),
) {
    fun node(id: String): DraftNode? = nodes.firstOrNull { it.id == id }

    /** The edges leaving [nodeId]. */
    fun edgesFrom(nodeId: String): List<DraftEdge> = edges.filter { it.from == nodeId }

    /** Where [outcome] of [nodeId] goes: its own edge, else the default one (`next`, not out of a check's branches). */
    fun edgeOf(nodeId: String, outcome: String): DraftEdge? {
        val out = edgesFrom(nodeId)
        val branches = node(nodeId)?.branches == true
        return out.firstOrNull { it.outcome == outcome } ?: out.firstOrNull { it.outcome == DraftNode.NEXT }.takeIf { !branches }
    }

    /** The first node: [start], else the first one. */
    val first: String? get() = start ?: nodes.firstOrNull()?.id

    companion object {
        const val SAME_SESSION = "same"
    }
}

/** A saved workflow to change: [version] is the one the editor read and sends back, so a save over a newer one is refused. */
data class EditableWorkflow(val id: String, val version: Int?, val draft: WorkflowDraft)

/**
 * Who may decide workflows besides owners and admins ([mode]: `all`, `admins` or `members`, with [memberCount]
 * members), and whether the caller may ([mayDecide]: a workflow without a gate node; a gated one stays an owner's or
 * an admin's). The rule is changed on the console only.
 */
data class WorkflowEditRights(val mayDecide: Boolean, val mode: String, val memberCount: Int)

/**
 * A governance write, applied at once ([Applied]) or held ([Held]): a write made with a person's API token opens a
 * confirmation that an owner or an admin confirms on the console at [Held.confirmUrl] before [Held.expiresAt].
 */
sealed interface WorkflowWrite<out T> {
    data class Applied<out T>(val value: T) : WorkflowWrite<T>

    data class Held(val confirmationId: String, val confirmUrl: String, val expiresAt: Long?) : WorkflowWrite<Nothing>
}

/** Why the server refused to create, save or delete a workflow, by its error key; worded by the app. */
enum class WorkflowEditRefusal {
    /** `403 workflow_editors_only`: the person may not decide this workflow (not an editor, or it holds a gate). */
    EDITORS_ONLY,

    /** `409 workflow_conflict`: someone saved it since it was read. */
    STALE,

    /** `409 workflow_name_taken`. */
    NAME_TAKEN,

    /** `409 conflict` on a creation: a workflow with this id exists. */
    ID_TAKEN,

    /** `409 workflow_limit`: the organization keeps as many workflows as it may. */
    LIMIT,

    /** `429 confirmation_limit`: too many changes wait for a confirmation on the console. */
    CONFIRMATION_LIMIT,

    /** `400 invalid_workflow` or `invalid_request`: the server refused the definition. */
    INVALID,

    /** `409 workflow_in_use`: a package, an automation or another workflow uses it. */
    IN_USE,
}
