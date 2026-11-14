// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's workflow editor; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.repositories.WorkflowEditorRepository
import run.ostrom.app.domain.services.Clock

/** A workflow as the person left it in the demo: whole, with the read model the pages show. */
internal data class EditedWorkflow(val editable: EditableWorkflow, val workflow: Workflow)

/** The demo's workflows: the built-in ones as the person changed them, then those they created; without the deleted ones. */
internal fun demoWorkflows(store: DemoStore): List<Workflow> {
    val edited = store.editedWorkflows.value
    val deleted = store.deletedWorkflows.value
    val builtIn = builtInWorkflows().map { edited[it.id]?.workflow ?: it }
    val created = edited.values.map { it.workflow }.filter { created -> builtInWorkflows().none { it.id == created.id } }
    return (builtIn + created).filter { it.id !in deleted }
}

/** A built-in workflow as the editor opens it: its nodes in order, each leading to the next, the last ending the run. */
private fun Workflow.editable(): EditableWorkflow {
    val nodes = nodes.map { node ->
        DraftNode(
            id = node.id, kind = node.kind, title = node.label,
            instruction = if (node.kind == NodeKind.AGENT) node.detail.orEmpty() else "",
            question = if (node.kind == NodeKind.WAIT) node.label else "",
            deliverable = if (node.kind == NodeKind.AGENT) DraftDeliverable("text") else null,
            originId = node.id,
        )
    }
    val edges = nodes.mapIndexed { i, node -> DraftEdge(node.id, nodes.getOrNull(i + 1)?.id) }
    return EditableWorkflow(id, version, WorkflowDraft(name, description.orEmpty(), domain, start, WorkflowDraft.SAME_SESSION, inputs, nodes, edges))
}

/**
 * The demo's editor, from memory: who may decide (the person, an owner), the workflows whole, and their writes applied
 * at once with the version raised; a save over an older version is refused as a server does. Nothing leaves the device.
 */
class DemoWorkflowEditorRepository(private val demo: DemoMode, private val clock: Clock) : WorkflowEditorRepository {
    override suspend fun rights(accountId: String): WorkflowEditRights {
        demo.store()
        return WorkflowEditRights(mayDecide = true, mode = "admins", memberCount = 0)
    }

    override suspend fun editable(accountId: String, workflowId: String): EditableWorkflow {
        val store = demo.store()
        if (workflowId in store.deletedWorkflows.value) throw AppException.NotFound()
        return store.editedWorkflows.value[workflowId]?.editable
            ?: builtInWorkflows().firstOrNull { it.id == workflowId }?.editable()
            ?: throw AppException.NotFound()
    }

    override suspend fun create(accountId: String, draft: WorkflowDraft): WorkflowWrite<String> {
        val store = demo.store()
        val id = "demo-workflow-new-${store.editedWorkflows.value.size + 1}"
        keep(store, EditableWorkflow(id, 1, draft), "accepted")
        return WorkflowWrite.Applied(id)
    }

    override suspend fun save(accountId: String, workflow: EditableWorkflow, draft: WorkflowDraft): WorkflowWrite<String> {
        val store = demo.store()
        val current = editable(accountId, workflow.id)
        if (current.version != workflow.version) throw AppException.WorkflowEditRefused(WorkflowEditRefusal.STALE)
        val status = demoWorkflows(store).firstOrNull { it.id == workflow.id }?.status ?: "accepted"
        keep(store, EditableWorkflow(workflow.id, (workflow.version ?: 0) + 1, draft), status)
        return WorkflowWrite.Applied(workflow.id)
    }

    override suspend fun delete(accountId: String, workflowId: String): WorkflowWrite<Unit> {
        val store = demo.store()
        editable(accountId, workflowId)
        store.deletedWorkflows.value = store.deletedWorkflows.value + workflowId
        return WorkflowWrite.Applied(Unit)
    }

    private fun keep(store: DemoStore, editable: EditableWorkflow, status: String) {
        val draft = editable.draft
        val workflow = Workflow(
            editable.id, draft.name.trim(), draft.domain, draft.description.ifBlank { null }, editable.version, emptyList(), status, draft.start, draft.inputs,
            draft.nodes.map { WorkflowNode(it.id, it.kind, it.title, it.instruction.ifBlank { null }, it.workflowId) }, clock.nowMillis(),
        )
        store.editedWorkflows.value = store.editedWorkflows.value + (editable.id to EditedWorkflow(editable, workflow))
    }
}

/** The demo account's workflow definitions from memory, every other account's from its server. */
class DemoAwareWorkflowEditorRepository(private val real: WorkflowEditorRepository, private val demo: WorkflowEditorRepository) : WorkflowEditorRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun rights(accountId: String) = of(accountId).rights(accountId)
    override suspend fun editable(accountId: String, workflowId: String) = of(accountId).editable(accountId, workflowId)
    override suspend fun create(accountId: String, draft: WorkflowDraft) = of(accountId).create(accountId, draft)
    override suspend fun save(accountId: String, workflow: EditableWorkflow, draft: WorkflowDraft) = of(accountId).save(accountId, workflow, draft)
    override suspend fun delete(accountId: String, workflowId: String) = of(accountId).delete(accountId, workflowId)
}
