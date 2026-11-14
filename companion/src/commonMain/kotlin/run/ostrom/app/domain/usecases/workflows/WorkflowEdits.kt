// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/workflows/WorkflowEdits.kt; see NOTICE.
package run.ostrom.app.domain.usecases.workflows

import run.ostrom.app.domain.models.DEFAULT_INPUT_LENGTH
import run.ostrom.app.domain.models.DraftAction
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowInput

/**
 * The changes the editor makes to a draft, each keeping it whole: a renamed node keeps its edges, the start and the
 * templates that name it; a removed node takes its edges with it; a new node is routed from the last one that ended.
 */
object WorkflowEdits {
    /** A new workflow: one agent step that ends it, ready to be described. */
    fun newDraft(): WorkflowDraft {
        val agent = DraftNode("agent", NodeKind.AGENT, "", deliverable = DraftDeliverable("text"))
        return WorkflowDraft(name = "", start = agent.id, nodes = listOf(agent), edges = listOf(DraftEdge(agent.id, null, DraftNode.DELIVERED)))
    }

    /** An id not used yet: [base], then `base2`, `base3`... */
    private fun fresh(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base$n" in taken) n++
        return "$base$n"
    }

    private fun base(kind: NodeKind) = when (kind) {
        NodeKind.WAIT -> "ask"
        NodeKind.WORKFLOW -> "call"
        else -> "agent"
    }

    /**
     * Adds a node of [kind] at the end. The edges that ended the workflow now lead to it, and it ends the workflow, so
     * the flow stays whole.
     */
    fun addNode(draft: WorkflowDraft, kind: NodeKind): WorkflowDraft {
        val id = fresh(base(kind), draft.nodes.map { it.id }.toSet())
        val node = blank(id, kind)
        val ending = draft.edges.filter { it.to == null }
        val edges = if (ending.isEmpty()) draft.edges else draft.edges.map { if (it.to == null) it.copy(to = id) else it }
        return draft.copy(
            nodes = draft.nodes + node,
            edges = edges + node.outcomes.map { DraftEdge(id, null, it.takeIf { o -> o != DraftNode.NEXT }) },
            start = draft.start ?: id,
        )
    }

    private fun blank(id: String, kind: NodeKind) = DraftNode(id, kind, "", deliverable = if (kind == NodeKind.AGENT) DraftDeliverable("text") else null)

    /**
     * Changes a node's kind: what the old kind held is dropped. Its default edge and the edges on an outcome the new
     * kind also has stay; every other outcome goes where its first edge went.
     */
    fun setKind(draft: WorkflowDraft, id: String, kind: NodeKind): WorkflowDraft {
        val node = draft.node(id) ?: return draft
        if (node.kind == kind) return draft
        val changed = blank(id, kind).copy(title = node.title, maxVisits = node.maxVisits, originId = node.originId)
        val out = draft.edgesFrom(id)
        val kept = out.filter { it.outcome == DraftNode.NEXT || it.outcome in changed.outcomes }
        val target = out.firstOrNull()?.to
        val covered = kept.any { it.outcome == DraftNode.NEXT }
        val added = if (out.isEmpty() || covered) emptyList() else changed.outcomes.filter { o -> kept.none { it.outcome == o } }.map { DraftEdge(id, target, it) }
        return draft.copy(nodes = draft.nodes.map { if (it.id == id) changed else it }, edges = draft.edges.filter { it.from != id } + kept + added)
    }

    /**
     * Removes a node and the edges that leave it. The edges that led to it go where it went (its first edge's target,
     * or the end), so the steps before it stay routed; the start moves to the first node left.
     */
    fun removeNode(draft: WorkflowDraft, id: String): WorkflowDraft {
        val nodes = draft.nodes.filter { it.id != id }
        val next = draft.edgesFrom(id).firstOrNull { it.to != id }?.to
        return draft.copy(
            nodes = nodes,
            edges = draft.edges.filter { it.from != id }.map { if (it.to == id) it.copy(to = next) else it },
            start = draft.start.takeIf { it != id } ?: nodes.firstOrNull()?.id,
        )
    }

    // `{{ <name> }}` with exactly [name] inside, spaces allowed as the server reads it.
    private fun placeholder(name: String) = Regex("\\{\\{(\\s*)" + Regex.escape(name) + "(\\s*)\\}\\}")

    private fun String.renamed(old: String, new: String) = replace(placeholder(old)) { "{{" + it.groupValues[1] + new + it.groupValues[2] + "}}" }

    /**
     * Renames a node: its edges, the start and the `{{nodes.<id>.output}}` that name it follow. A name another node
     * already has is not taken (it would merge the two), so the field keeps its value.
     */
    fun renameNode(draft: WorkflowDraft, old: String, new: String): WorkflowDraft {
        if (old == new || draft.nodes.any { it.id == new } || draft.nodes.count { it.id == old } != 1) return draft
        fun text(value: String) = value.renamed("nodes.$old.output", "nodes.$new.output")
        return draft.copy(
            nodes = draft.nodes.map { node ->
                val renamed = if (node.id == old) node.copy(id = new) else node
                renamed.copy(
                    instruction = text(renamed.instruction),
                    deliverable = renamed.deliverable?.let { d -> d.copy(label = d.label?.let(::text)) },
                    arguments = renamed.arguments.mapValues { text(it.value) },
                )
            },
            edges = draft.edges.map { edge -> edge.copy(from = if (edge.from == old) new else edge.from, to = if (edge.to == old) new else edge.to) },
            start = if (draft.start == old) new else draft.start,
        )
    }

    /** Moves a node up ([by] -1) or down (1) in the list; the flow does not change. */
    fun moveNode(draft: WorkflowDraft, id: String, by: Int): WorkflowDraft {
        val index = draft.nodes.indexOfFirst { it.id == id }
        val target = index + by
        if (index < 0 || target !in draft.nodes.indices) return draft
        val nodes = draft.nodes.toMutableList()
        nodes[index] = nodes[target].also { nodes[target] = nodes[index] }
        return draft.copy(nodes = nodes)
    }

    /** Sends [outcome] of [from] to [to] (null ends the workflow), replacing the edge that took it. */
    fun route(draft: WorkflowDraft, from: String, outcome: String, to: String?): WorkflowDraft {
        val kept = draft.edges.filterNot { it.from == from && it.outcome == outcome }
        return draft.copy(edges = kept + DraftEdge(from, to, outcome.takeIf { it != DraftNode.NEXT }))
    }

    /** A new input id: `input`, `input2`... */
    fun newInput(draft: WorkflowDraft): WorkflowInput =
        WorkflowInput(fresh("input", draft.inputs.map { it.id }.toSet()), "", required = true, default = null, maxLength = DEFAULT_INPUT_LENGTH)

    /** Renames an input: the `{{inputs.<id>}}` that name it follow. An id another input has is not taken. */
    fun renameInput(draft: WorkflowDraft, old: String, new: String): WorkflowDraft {
        if (old == new || draft.inputs.any { it.id == new } || draft.inputs.count { it.id == old } != 1) return draft
        fun text(value: String) = value.renamed("inputs.$old", "inputs.$new")
        return draft.copy(
            inputs = draft.inputs.map { if (it.id == old) it.copy(id = new) else it },
            nodes = draft.nodes.map { node ->
                node.copy(
                    instruction = text(node.instruction),
                    deliverable = node.deliverable?.let { d -> d.copy(label = d.label?.let(::text)) },
                    arguments = node.arguments.mapValues { text(it.value) },
                )
            },
        )
    }

    /** A new button for a wait node; its id is a new outcome, routed where the node's outcomes went. */
    fun addAction(draft: WorkflowDraft, nodeId: String): WorkflowDraft {
        val node = draft.node(nodeId) ?: return draft
        val action = DraftAction(fresh("action", node.actions.map { it.id }.toSet()), "")
        val target = draft.edgesFrom(nodeId).firstOrNull()?.to
        // The first action replaces the implicit `done`, which leaves the outcomes.
        val edges = if (node.actions.isEmpty()) draft.edges.filterNot { it.from == nodeId && it.outcome == DraftNode.DONE } else draft.edges
        return draft.copy(
            nodes = draft.nodes.map { if (it.id == nodeId) it.copy(actions = it.actions + action) else it },
            edges = edges + DraftEdge(nodeId, target, action.id),
        )
    }

    /** Renames a wait's action: the edge on its outcome follows. An id another button of the node has is not taken. */
    fun renameAction(draft: WorkflowDraft, nodeId: String, old: String, new: String): WorkflowDraft {
        val actions = draft.node(nodeId)?.actions.orEmpty()
        if (old == new || actions.any { it.id == new } || actions.count { it.id == old } != 1) return draft
        return renamedAction(draft, nodeId, old, new)
    }

    private fun renamedAction(draft: WorkflowDraft, nodeId: String, old: String, new: String): WorkflowDraft = draft.copy(
        nodes = draft.nodes.map { node -> if (node.id == nodeId) node.copy(actions = node.actions.map { if (it.id == old) it.copy(id = new) else it }) else node },
        edges = draft.edges.map { if (it.from == nodeId && it.on == old) it.copy(on = new) else it },
    )

    /** Removes a wait's action and the edge on its outcome. */
    fun removeAction(draft: WorkflowDraft, nodeId: String, id: String): WorkflowDraft = draft.copy(
        nodes = draft.nodes.map { node -> if (node.id == nodeId) node.copy(actions = node.actions.filterNot { it.id == id }) else node },
        edges = draft.edges.filterNot { it.from == nodeId && it.on == id },
    )

    /** The templates a node's texts may use: every input, and every other node's output. */
    fun templates(draft: WorkflowDraft, nodeId: String): List<String> =
        draft.inputs.filter { it.id.isNotBlank() }.map { "{{inputs.${it.id}}}" } + draft.nodes.filter { it.id != nodeId }.map { "{{nodes.${it.id}.output}}" }
}
