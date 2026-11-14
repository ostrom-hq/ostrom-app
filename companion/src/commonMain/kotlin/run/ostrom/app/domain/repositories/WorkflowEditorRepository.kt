// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/WorkflowRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowWrite

/**
 * The workflow definitions as the editor changes them (`workflows.v1`): read whole, created, saved over the version
 * read, deleted. Each write is a governance write: applied, or held for an owner or an admin to confirm on the console.
 * A refusal is an [run.ostrom.app.domain.models.AppException.WorkflowEditRefused].
 */
interface WorkflowEditorRepository {
    /** Who may decide workflows, and whether the caller may. */
    suspend fun rights(accountId: String): WorkflowEditRights

    /** A saved workflow, whole. */
    suspend fun editable(accountId: String, workflowId: String): EditableWorkflow

    /** Creates a workflow from [draft]; the answer is the id it was saved under. */
    suspend fun create(accountId: String, draft: WorkflowDraft): WorkflowWrite<String>

    /**
     * Saves [draft] over [workflow] (read at [EditableWorkflow.version]): what the editor does not change is kept as
     * stored. A newer stored version is refused.
     */
    suspend fun save(accountId: String, workflow: EditableWorkflow, draft: WorkflowDraft): WorkflowWrite<String>

    suspend fun delete(accountId: String, workflowId: String): WorkflowWrite<Unit>
}
