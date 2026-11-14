// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/workflows/WorkflowChecks.kt; see NOTICE.
package run.ostrom.app.domain.usecases.workflows

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.ProblemRule
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowProblem

/**
 * The checks the server makes at save (ostrom-server `WorkflowChecks`, `NodeChecks` and `CallChecks`), run here on the
 * draft: while the person edits, to show each refusal against its node and keep Save off, and again before anything
 * is sent. As on the server they apply to a workflow the server plays (one with an agent, a wait or a workflow node);
 * a workflow of the harness only keeps its looser rules, but always needs a name. [others] are the organization's
 * other workflows for the call checks (existence, loops, depth, arguments) and the free name; null when they could not
 * be read, which skips those checks (the server still makes them).
 */
object WorkflowChecks {
    const val MAX_NAME = 120
    const val MAX_DESCRIPTION = 2_000
    const val MAX_TITLE = 200
    const val MAX_INPUTS = 16
    const val MAX_NODES = 100
    const val DEFAULT_NODES = 50
    const val MAX_TOTAL_VISITS = 500
    const val DEFAULT_VISITS = 100
    const val MAX_DEPTH = 5
    const val DEFAULT_DEPTH = 3
    const val MAX_NODE_VISITS = 20
    const val MAX_INPUT_LENGTH = 32_000
    const val MAX_ACTIONS = 8

    private val identifier = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val nodeIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    private val placeholder = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
    private val inputRef = Regex("inputs\\.([^.]+)")
    private val nodeRef = Regex("nodes\\.([^.]+)\\.output")
    private val domainName = Regex("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
    private val serverKinds = setOf(NodeKind.AGENT, NodeKind.WAIT, NodeKind.WORKFLOW)

    /** Whether the server plays [draft] (an agent, a wait or a workflow node), so its save checks apply. */
    fun runsOnServer(draft: WorkflowDraft): Boolean = draft.nodes.any { it.kind in serverKinds }

    /** `inputs.x` and `nodes.id.output` named in [text]; anything else between double braces is refused. */
    fun placeholders(text: String): List<String> = placeholder.findAll(text).map { it.groupValues[1] }.toList()

    /** Whether [value] parses as a JSON object, for a deliverable's schema. */
    fun isJsonObject(value: String): Boolean = try {
        Json.parseToJsonElement(value) is JsonObject
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Every problem of [draft]; [selfId] is the id of the workflow changed (null while creating). */
    fun check(draft: WorkflowDraft, others: List<Workflow>? = null, selfId: String? = null): List<WorkflowProblem> {
        val sink = Sink(null)
        val name = draft.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME || draft.name.any { it.isISOControl() }) sink.add(ProblemRule.NAME, MAX_NAME.toString())
        if (!runsOnServer(draft)) return sink.out
        if (others != null && others.any { it.id != selfId && it.name.trim().equals(name, ignoreCase = true) }) sink.add(ProblemRule.NAME_TAKEN)
        if (draft.description.length > MAX_DESCRIPTION) sink.add(ProblemRule.DESCRIPTION, MAX_DESCRIPTION.toString())
        val limits = draft.limits
        val limitsOk = (limits.maxNodes ?: DEFAULT_NODES) in 1..MAX_NODES && (limits.maxVisits ?: DEFAULT_VISITS) in 1..MAX_TOTAL_VISITS &&
            (limits.maxDepth ?: DEFAULT_DEPTH) in 0..MAX_DEPTH
        if (!limitsOk) sink.add(ProblemRule.LIMITS, MAX_NODES.toString(), MAX_TOTAL_VISITS.toString(), MAX_DEPTH.toString())
        inputs(draft, sink)
        graph(draft, limitsOk, sink)
        val ids = draft.nodes.map { it.id }.toSet()
        val inputIds = draft.inputs.map { it.id }.toSet()
        val out = sink.out.toMutableList()
        for (node in draft.nodes) {
            val nodeSink = Sink(node.id)
            node(node, ids, inputIds, draft.edgesFrom(node.id), nodeSink)
            if (others != null) calls(node, draft, others, selfId, nodeSink)
            out += nodeSink.out
        }
        return out
    }

    private class Sink(val nodeId: String?) {
        val out = mutableListOf<WorkflowProblem>()
        fun add(rule: ProblemRule, vararg args: String) {
            out += WorkflowProblem(rule, nodeId, args.toList())
        }
    }

    private fun inputs(draft: WorkflowDraft, sink: Sink) {
        if (draft.inputs.size > MAX_INPUTS) sink.add(ProblemRule.TOO_MANY_INPUTS, MAX_INPUTS.toString())
        draft.inputs.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { sink.add(ProblemRule.INPUT_DUPLICATE, it) }
        for (input in draft.inputs) {
            val name = input.id.ifBlank { "?" }
            if (!identifier.matches(input.id)) sink.add(ProblemRule.INPUT_ID, name)
            if (input.label.trim().length !in 1..MAX_TITLE) sink.add(ProblemRule.INPUT_LABEL, name, MAX_TITLE.toString())
            if (input.maxLength !in 1..MAX_INPUT_LENGTH) {
                sink.add(ProblemRule.INPUT_MAX_LENGTH, name, MAX_INPUT_LENGTH.toString())
            } else if (input.default.orEmpty().length > input.maxLength) {
                sink.add(ProblemRule.INPUT_DEFAULT_LONG, name)
            }
        }
    }

    private fun graph(draft: WorkflowDraft, limitsOk: Boolean, sink: Sink) {
        val nodes = draft.nodes
        val ids = nodes.map { it.id }
        val maxNodes = draft.limits.maxNodes ?: DEFAULT_NODES
        if (limitsOk && nodes.size > maxNodes) sink.add(ProblemRule.TOO_MANY_NODES, maxNodes.toString())
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { sink.add(ProblemRule.NODE_DUPLICATE, it) }
        draft.edges.filter { it.to != null && it.to !in ids }.forEach { sink.add(ProblemRule.TRANSITION_TARGET, it.from, it.to.orEmpty()) }
        val start = draft.first ?: return
        val first = draft.node(start)
        if (first == null) {
            sink.add(ProblemRule.START_MISSING, start)
            return
        }
        if (first.kind == NodeKind.WAIT) sink.add(ProblemRule.START_WAIT, first.title.ifBlank { first.id })
        val reached = reachable(start, draft.edges)
        nodes.filter { it.id !in reached }.forEach { sink.add(ProblemRule.NOT_REACHABLE, it.title.ifBlank { it.id }) }
        val ends = draft.edges.any { it.to == null } || nodes.any { node -> draft.edges.none { it.from == node.id } }
        if (!ends) sink.add(ProblemRule.NO_ENDING)
    }

    /** Every node id reached from [start] along [edges]. */
    fun reachable(start: String, edges: List<DraftEdge>): Set<String> {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque(listOf(start))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (seen.add(id)) stack.addAll(edges.filter { it.from == id }.mapNotNull { it.to })
        }
        return seen
    }

    private fun node(node: DraftNode, ids: Set<String>, inputs: Set<String>, out: List<DraftEdge>, sink: Sink) {
        if (!nodeIdentifier.matches(node.id)) sink.add(ProblemRule.NODE_ID, node.id)
        if (node.title.trim().length !in 1..MAX_TITLE) sink.add(ProblemRule.TITLE, MAX_TITLE.toString())
        if ((node.maxVisits ?: 1) !in 1..MAX_NODE_VISITS) sink.add(ProblemRule.MAX_VISITS, MAX_NODE_VISITS.toString())
        templates(node).flatMap(::placeholders).distinct().forEach { name ->
            val input = inputRef.matchEntire(name)?.groupValues?.get(1)
            val other = nodeRef.matchEntire(name)?.groupValues?.get(1)
            val known = when {
                input != null -> input in inputs
                other != null -> other in ids && other != node.id
                else -> false
            }
            if (!known) sink.add(ProblemRule.TEMPLATE_UNKNOWN, name)
        }
        when (node.kind) {
            NodeKind.AGENT -> agent(node, sink)
            NodeKind.WAIT -> wait(node, sink)
            NodeKind.WORKFLOW -> {
                if (node.workflowId.isNullOrBlank()) sink.add(ProblemRule.CALLEE_MISSING)
                node.arguments.keys.filterNot { identifier.matches(it) }.forEach { sink.add(ProblemRule.ARGUMENT_NAME, it) }
            }
            else -> Unit
        }
        val played = node.kind in serverKinds || (node.kind == NodeKind.CHECK && node.branches)
        if (played) edges(node, out, sink)
    }

    // The texts a template renders in: an agent's instructions, a wait's body, a deliverable's label, an argument.
    private fun templates(node: DraftNode): List<String> = when (node.kind) {
        NodeKind.AGENT -> listOfNotNull(node.instruction, node.deliverable?.label)
        NodeKind.WAIT -> listOf(node.instruction)
        NodeKind.WORKFLOW -> node.arguments.values.toList()
        else -> emptyList()
    }

    private fun agent(node: DraftNode, sink: Sink) {
        if (node.instruction.isBlank()) sink.add(ProblemRule.AGENT_INSTRUCTION)
        val deliverable = node.deliverable ?: return
        if (deliverable.label.orEmpty().length > MAX_TITLE) sink.add(ProblemRule.DELIVERABLE_LABEL, MAX_TITLE.toString())
        val domain = deliverable.domain
        if (!domain.isNullOrEmpty() && (deliverable.type != "url" || !domainName.matches(domain))) sink.add(ProblemRule.DELIVERABLE_DOMAIN)
        val schema = deliverable.schema
        if (!schema.isNullOrBlank()) {
            if (deliverable.type != "json") sink.add(ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON) else if (!isJsonObject(schema)) sink.add(ProblemRule.DELIVERABLE_SCHEMA_INVALID)
        }
    }

    private fun wait(node: DraftNode, sink: Sink) {
        if (node.question.trim().length !in 1..MAX_TITLE) sink.add(ProblemRule.WAIT_QUESTION, MAX_TITLE.toString())
        if (node.actions.size > MAX_ACTIONS) sink.add(ProblemRule.ACTIONS_TOO_MANY, MAX_ACTIONS.toString())
        node.actions.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { sink.add(ProblemRule.ACTION_DUPLICATE, it) }
        node.actions.filterNot { identifier.matches(it.id) }.forEach { sink.add(ProblemRule.ACTION_ID, it.id.ifBlank { "?" }) }
    }

    // Every edge takes an outcome of the node, no two the same, and every outcome goes somewhere: its own edge or the
    // default one (`next`). A node with no edge ends the run, but a check's branches, whose false ends nothing.
    private fun edges(node: DraftNode, out: List<DraftEdge>, sink: Sink) {
        val outcomes = node.outcomes.toSet()
        val taken = out.map { it.outcome }
        val allowed = if (node.branches) outcomes else outcomes + DraftNode.NEXT
        taken.filter { it !in allowed }.distinct().forEach { sink.add(ProblemRule.TRANSITION_OUTCOME, it, allowed.sorted().joinToString(", ")) }
        taken.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { sink.add(ProblemRule.TRANSITION_DUPLICATE, it) }
        if (out.isEmpty() && !node.branches) return
        outcomes.sorted().filter { it !in taken && (node.branches || DraftNode.NEXT !in taken) }.forEach { sink.add(ProblemRule.TRANSITION_MISSING, it) }
    }

    private fun calls(node: DraftNode, draft: WorkflowDraft, others: List<Workflow>, selfId: String?, sink: Sink) {
        val callee = node.workflowId?.takeIf { node.kind == NodeKind.WORKFLOW && it.isNotBlank() } ?: return
        if (callee == selfId) {
            sink.add(ProblemRule.CALLEE_SELF)
            return
        }
        val byId = others.filter { it.id != selfId }.associateBy { it.id }
        val called = byId[callee]
        if (called == null) {
            sink.add(ProblemRule.CALLEE_UNKNOWN, callee)
            return
        }
        val deepest = depth(callee, listOfNotNull(selfId), byId, sink) ?: return
        val maxDepth = draft.limits.maxDepth ?: DEFAULT_DEPTH
        if (deepest > maxDepth) sink.add(ProblemRule.CALLEE_DEPTH, deepest.toString(), maxDepth.toString())
        val given = node.arguments.keys
        called.inputs.filter { it.required && it.default == null && it.id !in given }.forEach { sink.add(ProblemRule.ARGUMENT_MISSING, it.id, called.name) }
        given.filter { key -> called.inputs.none { it.id == key } }.forEach { sink.add(ProblemRule.ARGUMENT_UNKNOWN, it, called.name) }
    }

    // How deep the chain below [id] goes; null after a loop (reported with its path).
    private fun depth(id: String, path: List<String>, others: Map<String, Workflow>, sink: Sink): Int? {
        if (id in path) {
            sink.add(ProblemRule.CALLEE_LOOP, (path + id).joinToString(" -> "))
            return null
        }
        var deepest = 0
        for (next in others[id]?.nodes.orEmpty().mapNotNull { it.calls }.toSet()) {
            deepest = maxOf(deepest, depth(next, path + id, others, sink) ?: return null)
        }
        return 1 + deepest
    }
}
