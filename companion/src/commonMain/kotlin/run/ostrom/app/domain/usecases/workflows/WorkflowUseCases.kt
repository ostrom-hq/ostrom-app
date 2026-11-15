// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/workflows/WorkflowUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.workflows

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.RunFilter
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.repositories.find
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
 * Whether the person may start and cancel runs: the server lets an owner or an admin of the organization do it
 * (`403 forbidden` otherwise). Their role is read from `GET /me/organizations`; when it cannot be read (offline, a
 * server that does not say) the actions show and a refusal is worded by its key.
 */
class CanManageRunsUseCase(private val auth: AuthRepository, private val accounts: AccountRepository) {
    suspend operator fun invoke(accountId: String): Boolean {
        val account = accounts.find(accountId)?.takeIf { it.signedIn } ?: return false
        val role = try {
            val organizations = auth.organizations(accountId)
            val current = account.organizationId?.let { id -> organizations.firstOrNull { it.id == id } } ?: organizations.singleOrNull()
            current?.role
        } catch (error: CancellationException) {
            throw error
        } catch (_: AppException.Unauthorized) {
            // Signed out meanwhile: no action to offer.
            return false
        } catch (_: Exception) {
            null
        }
        return role == null || role in MANAGERS
    }

    private companion object {
        val MANAGERS = setOf("owner", "admin")
    }
}

/**
 * Checks what is entered before anything is sent (the server checks again and its refusals are worded by key): a
 * value for each required input (its default counts), none longer than its maximum. Returns the inputs to send:
 * trimmed, without the empty optional ones.
 */
fun checkRunStart(workflow: Workflow, entered: Map<String, String>): Map<String, String> {
    val sent = linkedMapOf<String, String>()
    for (input in workflow.inputs) {
        val value = (entered[input.id] ?: input.default.orEmpty()).trim()
        if (value.isEmpty() && input.required) throw AppException.WorkflowInputMissing(input.label)
        if (value.length > input.maxLength) throw AppException.WorkflowRefused(WorkflowRefusal.INVALID_INPUTS)
        if (value.isNotEmpty()) sent[input.id] = value
    }
    return sent
}

/**
 * Starts a run of an accepted [workflow] as the person, with what they [entered] (by input id). The caller keeps
 * [idempotencyKey] while the form is unchanged, so pressing Start again after a lost answer returns the first run.
 */
class StartWorkflowRunUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(
        accountId: String,
        workflow: Workflow,
        entered: Map<String, String>,
        idempotencyKey: String,
    ): WorkflowRun {
        if (!workflow.runnable) throw AppException.WorkflowRefused(WorkflowRefusal.NOT_RUNNABLE)
        val inputs = checkRunStart(workflow, entered)
        return access.call(accountId) { access.repository.start(accountId, workflow.id, inputs, idempotencyKey) }
    }
}

/** Cancels a run that has not ended; the engine ends it on its next pass. The answer carries no visits. */
class CancelWorkflowRunUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, run: WorkflowRun): WorkflowRun {
        if (!run.status.active) throw AppException.WorkflowRefused(WorkflowRefusal.RUN_ENDED)
        return access.call(accountId) { access.repository.cancel(accountId, run.id) }
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
