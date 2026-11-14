// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/WorkflowRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage

/** The workflows of an account's server and their runs (`workflows.v1`): read, and runs started and cancelled. */
interface WorkflowRepository {
    /** The organization's saved workflows. */
    suspend fun workflows(accountId: String): List<Workflow>

    suspend fun workflow(accountId: String, workflowId: String): Workflow

    /**
     * A page of runs, newest first: of [workflowId], or the member's own when it is null; only those in [status] when
     * it is set; [before] is the previous page's cursor.
     */
    suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?): WorkflowRunPage

    /** One run, with its visits. */
    suspend fun run(accountId: String, runId: String): WorkflowRun

    /** Starts a run of [workflowId] as the person with [inputs] (by input id); a refusal is an [run.ostrom.app.domain.models.AppException.WorkflowRefused]. */
    suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>): WorkflowRun

    /** Cancels [runId]; the answer is the run as cancelled, without its visits. */
    suspend fun cancel(accountId: String, runId: String): WorkflowRun
}
