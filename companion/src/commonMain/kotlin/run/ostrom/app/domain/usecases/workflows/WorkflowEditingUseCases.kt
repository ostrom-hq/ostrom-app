// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/workflows/WorkflowEditingUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.workflows

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.repositories.WorkflowEditorRepository

/** Who may decide workflows, and whether the person may: any member reads it. */
class LoadWorkflowEditRightsUseCase(private val access: WorkflowAccess, private val editor: WorkflowEditorRepository) {
    suspend operator fun invoke(accountId: String): WorkflowEditRights = access.call(accountId) { editor.rights(accountId) }
}

/** A saved workflow, whole, to change it. A kind a newer server added is not edited here (it would be saved as another kind). */
class LoadEditableWorkflowUseCase(private val access: WorkflowAccess, private val editor: WorkflowEditorRepository) {
    suspend operator fun invoke(accountId: String, workflowId: String): EditableWorkflow {
        if (workflowId.isBlank()) throw AppException.NotFound()
        val loaded = access.call(accountId) { editor.editable(accountId, workflowId) }
        if (loaded.draft.nodes.any { it.kind == NodeKind.UNKNOWN }) throw AppException.Unsupported("workflow.kind")
        return loaded
    }
}

/**
 * Saves a draft: the server's checks are made here first ([WorkflowChecks], with the organization's [others] workflows
 * for the call rules), so a draft that would be refused is refused with the node it concerns and nothing is sent. A
 * new workflow is created; a changed one is saved over the version read, so a save over someone else's is refused
 * (`workflow_conflict`). Either may be held for a confirmation on the console.
 */
class SaveWorkflowUseCase(private val access: WorkflowAccess, private val editor: WorkflowEditorRepository) {
    suspend fun create(accountId: String, draft: WorkflowDraft, others: List<Workflow>?): WorkflowWrite<String> {
        check(draft, others, null)
        return access.call(accountId) { editor.create(accountId, draft) }
    }

    suspend fun update(accountId: String, workflow: EditableWorkflow, draft: WorkflowDraft, others: List<Workflow>?): WorkflowWrite<String> {
        check(draft, others, workflow.id)
        return access.call(accountId) { editor.save(accountId, workflow, draft) }
    }

    private fun check(draft: WorkflowDraft, others: List<Workflow>?, selfId: String?) {
        val problems = WorkflowChecks.check(draft, others, selfId)
        if (problems.isNotEmpty()) throw AppException.WorkflowInvalid(problems)
    }
}

/** Deletes a workflow (runs in flight keep the copy they started with); refused while something uses it. */
class DeleteWorkflowUseCase(private val access: WorkflowAccess, private val editor: WorkflowEditorRepository) {
    suspend operator fun invoke(accountId: String, workflowId: String): WorkflowWrite<Unit> {
        if (workflowId.isBlank()) throw AppException.NotFound()
        return access.call(accountId) { editor.delete(accountId, workflowId) }
    }
}

/**
 * The link of a held write's confirmation, only when it opens on the account's own server (where its console is):
 * anything else a server sends is not opened from the app.
 */
fun confirmationLink(origin: String, url: String): String? {
    val base = origin.trimEnd('/')
    if (base.isEmpty() || url.any { it.isWhitespace() || it.isISOControl() }) return null
    return url.takeIf { it == base || it.startsWith("$base/") }
}
