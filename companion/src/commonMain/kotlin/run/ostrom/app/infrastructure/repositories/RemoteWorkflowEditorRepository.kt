// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteWorkflowRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlinx.serialization.json.Json
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DraftAction
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftLimits
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.repositories.WorkflowEditorRepository
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowLimits
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.core.client.GovernedWrite
import run.ostrom.core.client.OstromException
import kotlin.time.Instant
import run.ostrom.core.api.models.Workflow as ApiWorkflow
import run.ostrom.core.api.models.WorkflowInput as ApiWorkflowInput
import run.ostrom.core.api.models.WorkflowNode as ApiWorkflowNode

/**
 * The workflow definitions over the typed client (`/api/v1/workflows`, `/api/v1/workflows/settings`). A save reads
 * the stored workflow first and changes only what the editor edits, so what it does not edit (a gate's approvers, a
 * wait's fields, the analysis' suggestions, positions) is kept as far as the pinned core's model knows it; the version
 * read is checked and sent, so a newer one is refused.
 */
class RemoteWorkflowEditorRepository(
    private val calls: AccountCalls,
    private val clock: Clock,
    private val ids: IdGenerator,
) : WorkflowEditorRepository {
    override suspend fun rights(accountId: String): WorkflowEditRights = calls.withAccount(accountId) { client ->
        val settings = client.workflowSettings.get()
        WorkflowEditRights(settings.canEdit, settings.editors.mode, settings.editors.memberIds.orEmpty().size)
    }

    override suspend fun editable(accountId: String, workflowId: String): EditableWorkflow =
        calls.withAccount(accountId, resource = true) { it.workflows.get(workflowId).editable() }

    override suspend fun create(accountId: String, draft: WorkflowDraft): WorkflowWrite<String> = calls.withAccount(accountId) { client ->
        val workflow = draft.apply(null, "wf_${ids.newId().filter { it.isLetterOrDigit() }.take(ID_LENGTH)}", now())
        refusals(creating = true) { client.workflows.create(workflow).domain { it.id } }
    }

    override suspend fun save(accountId: String, workflow: EditableWorkflow, draft: WorkflowDraft): WorkflowWrite<String> =
        calls.withAccount(accountId, resource = true) { client ->
            val stored = client.workflows.get(workflow.id)
            // Saved since it was read: refused here too, so a read without a version never overwrites a newer one.
            if (stored.version != workflow.version) throw AppException.WorkflowEditRefused(WorkflowEditRefusal.STALE)
            val saved = draft.apply(stored, workflow.id, now())
            refusals(creating = false) { client.workflows.save(workflow.id, saved, workflow.version).domain { it.id } }
        }

    override suspend fun delete(accountId: String, workflowId: String): WorkflowWrite<Unit> =
        calls.withAccount(accountId, resource = true) { client -> refusals(creating = false) { client.workflows.delete(workflowId).domain { } } }

    private fun now() = Instant.fromEpochMilliseconds(clock.nowMillis())

    /** The refusals of a write, by their error key; the server's message is read only for its leading key, never shown. */
    private suspend fun <T> refusals(creating: Boolean, call: suspend () -> T): T = try {
        call()
    } catch (error: OstromException) {
        throw refusalOf(error, creating)?.let { AppException.WorkflowEditRefused(it) } ?: error
    }

    private fun refusalOf(error: OstromException, creating: Boolean): WorkflowEditRefusal? {
        val keys = setOfNotNull(error.type, error.message.orEmpty().substringBefore(':').trim())
        return when {
            "workflow_editors_only" in keys -> WorkflowEditRefusal.EDITORS_ONLY
            "workflow_conflict" in keys -> WorkflowEditRefusal.STALE
            "workflow_name_taken" in keys -> WorkflowEditRefusal.NAME_TAKEN
            "workflow_limit" in keys -> WorkflowEditRefusal.LIMIT
            "workflow_in_use" in keys -> WorkflowEditRefusal.IN_USE
            "confirmation_limit" in keys -> WorkflowEditRefusal.CONFIRMATION_LIMIT
            error.status == 400 && ("invalid_workflow" in keys || "invalid_request" in keys) -> WorkflowEditRefusal.INVALID
            creating && error.status == 409 -> WorkflowEditRefusal.ID_TAKEN
            else -> null
        }
    }

    private companion object {
        const val ID_LENGTH = 12
    }
}

private fun <T, R> GovernedWrite<T>.domain(value: (T) -> R): WorkflowWrite<R> = when (this) {
    is GovernedWrite.Applied -> WorkflowWrite.Applied(value(this.value))
    is GovernedWrite.Held -> WorkflowWrite.Held(confirmation.confirmationId, confirmation.confirmUrl, millisOf(confirmation.expiresAt))
}

private val schemaJson = Json

internal fun NodeKind.api(): WorkflowNodeKind = when (this) {
    NodeKind.TRIGGER -> WorkflowNodeKind.TRIGGER
    NodeKind.STEP -> WorkflowNodeKind.STEP
    NodeKind.CHECK -> WorkflowNodeKind.CHECK
    NodeKind.GATE -> WorkflowNodeKind.GATE
    NodeKind.NOTIFY -> WorkflowNodeKind.NOTIFY
    NodeKind.AGENT -> WorkflowNodeKind.AGENT
    NodeKind.WAIT -> WorkflowNodeKind.WAIT
    NodeKind.WORKFLOW -> WorkflowNodeKind.WORKFLOW
    NodeKind.UNKNOWN -> throw AppException.Unsupported("workflow.kind")
}

/** The stored workflow as the editor changes it. */
internal fun ApiWorkflow.editable() = EditableWorkflow(
    id = id, version = version,
    draft = WorkflowDraft(
        name = name, description = description.orEmpty(), domain = domain, start = start, sessionMode = sessionMode,
        inputs = inputs.map { WorkflowInput(it.id, it.label, it.required, it.default, it.maxLength) },
        nodes = nodes.map { node ->
            val kind = node.kind.domain()
            DraftNode(
                id = node.id, kind = kind, title = node.label,
                instruction = (if (kind == NodeKind.WAIT) node.wait?.body else node.run?.instructions).orEmpty(),
                question = node.wait?.title.orEmpty(), agent = node.run?.agent, model = node.run?.model,
                deliverable = node.run?.deliverable?.let { DraftDeliverable(it.type, it.label, it.domain, it.schema?.toString()) },
                actions = node.wait?.actions.orEmpty().map { DraftAction(it.id, it.label, it.style) },
                workflowId = node.workflowId, arguments = node.arguments.orEmpty(), maxVisits = node.maxVisits,
                branches = node.kind == WorkflowNodeKind.CHECK && node.condition != null, originId = node.id,
            )
        },
        edges = edges.map { DraftEdge(it.from, it.to, it.on, it.label) },
        limits = DraftLimits(limits?.maxNodes, limits?.maxVisits, limits?.maxDepth),
    ),
)

/**
 * The workflow to send: [stored] (null for a new one) with what the draft changes. A node keeps what it held under its
 * [DraftNode.originId] when its kind did not change; a new workflow is accepted, a person deciding it.
 */
internal fun WorkflowDraft.apply(stored: ApiWorkflow?, id: String, now: Instant): ApiWorkflow {
    val nodes = nodes.map { node ->
        val origin = stored?.nodes?.firstOrNull { it.id == node.originId }?.takeIf { it.kind.domain() == node.kind }
        node.apply(origin ?: ApiWorkflowNode(node.id, node.kind.api(), node.title))
    }
    val limits = limits.takeIf { it != DraftLimits() }?.let { WorkflowLimits(it.maxNodes, it.maxVisits, it.maxDepth) }
    val base = stored ?: ApiWorkflow(id = id, name = name, domain = domain, nodes = emptyList(), edges = emptyList(), status = WorkflowStatus.ACCEPTED, foundAt = now.toString())
    return base.copy(
        name = name.trim(), domain = domain.trim(), description = description.trim().ifEmpty { null }, start = start, sessionMode = sessionMode,
        inputs = inputs.map { ApiWorkflowInput(it.id, it.label.trim(), it.required, it.default?.ifEmpty { null }, it.maxLength) },
        nodes = nodes, edges = edges.map { WorkflowEdge(it.from, it.to, it.label, it.on) }, limits = limits,
    )
}

private fun DraftNode.apply(origin: ApiWorkflowNode): ApiWorkflowNode {
    val node = origin.copy(id = id, label = title.trim(), maxVisits = maxVisits)
    return when (kind) {
        NodeKind.AGENT -> node.copy(
            run = (node.run ?: NodeRun()).copy(
                instructions = instruction.ifBlank { null }, agent = agent?.trim()?.ifEmpty { null }, model = model?.trim()?.ifEmpty { null },
                deliverable = deliverable?.let { d ->
                    Deliverable(d.type, d.label?.trim()?.ifEmpty { null }, d.schema?.takeIf { it.isNotBlank() }?.let(schemaJson::parseToJsonElement), d.domain?.trim()?.ifEmpty { null })
                },
            ),
        )
        NodeKind.WAIT -> {
            val wait = node.wait ?: WaitSpec(question)
            val actions = actions.map { a -> WaitAction(a.id, a.label.trim(), wait.actions.firstOrNull { it.id == a.id }?.requires.orEmpty(), a.style) }
            node.copy(wait = wait.copy(title = question.trim(), body = instruction.ifBlank { null }, actions = actions))
        }
        NodeKind.WORKFLOW -> node.copy(workflowId = workflowId?.trim()?.ifEmpty { null }, arguments = arguments.ifEmpty { null })
        else -> node
    }
}
