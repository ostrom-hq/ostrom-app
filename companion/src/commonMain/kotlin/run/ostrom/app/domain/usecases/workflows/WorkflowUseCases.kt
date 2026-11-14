// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/workflows/WorkflowUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.workflows

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.RunFilter
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.events.signals

/** What the use cases share: the capability check and the account guard. */
class WorkflowAccess(val repository: WorkflowRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend fun <T> call(accountId: String, call: suspend () -> T): T = guard(accountId) {
        if (Features.WORKFLOWS !in features.capabilities(accountId).features) throw AppException.Unsupported(Features.WORKFLOWS)
        call()
    }
}

/** The saved workflows, by name. */
class LoadWorkflowsUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String): List<Workflow> =
        access.call(accountId) { access.repository.workflows(accountId).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }
}

class GetWorkflowUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, workflowId: String): Workflow {
        if (workflowId.isBlank()) throw AppException.NotFound()
        return access.call(accountId) { access.repository.workflow(accountId, workflowId) }
    }
}

/** A page of runs in [filter], newest first: of [workflowId], or the member's own when it is null. */
class LoadWorkflowRunsUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, workflowId: String?, filter: RunFilter, before: String? = null): WorkflowRunPage =
        access.call(accountId) { access.repository.runs(accountId, workflowId, filter.status, before) }
}

class GetWorkflowRunUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, runId: String): WorkflowRun {
        if (runId.isBlank()) throw AppException.NotFound()
        return access.call(accountId) { access.repository.run(accountId, runId) }
    }
}

/**
 * Keeps the runs current from the app's one feed ([OrgFeed]): a load when the feed opens, after a gap, and
 * [FeedSignal.Changed] at each `cloud.workflow.run.updated` event (its [OrgChange.subjectId] is the run, its
 * [OrgChange.workflowId] the workflow). Without the feed nothing more is followed (never polled): the runs load once
 * and on return to the screen. A server without `workflows.v1` yields nothing.
 */
class FollowWorkflowRunsUseCase(private val feed: OrgFeed, private val features: FeaturesRepository, private val guard: AccountGuard) {
    operator fun invoke(accountId: String): Flow<FeedSignal> = guard.flow(accountId, flow {
        if (Features.WORKFLOWS !in features.capabilities(accountId).features) return@flow
        emitAll(feed.signals(accountId, features, setOf(OrgChange.RUN_UPDATED)))
    })
}

/** Whether a run event concerns a list of [workflowId]'s runs (every run of the member's when null). */
fun OrgChange.concernsRunsOf(workflowId: String?): Boolean =
    type == OrgChange.RUN_UPDATED && (workflowId == null || this.workflowId == null || this.workflowId == workflowId)

/** Whether a run event concerns the run [runId]: the run itself, or one of its sub-workflows' runs it shows. */
fun OrgChange.concernsRun(run: WorkflowRun?, runId: String): Boolean =
    type == OrgChange.RUN_UPDATED && (subjectId == runId || run?.visits?.any { it.childRunId == subjectId } == true)
