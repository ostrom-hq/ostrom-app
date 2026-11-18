// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.aWorkflow
import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.playedDraft
import run.ostrom.app.domain.repositories.WorkflowEditorRepository
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.workflows.CanManageRunsUseCase
import run.ostrom.app.domain.usecases.workflows.CancelWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.DeleteWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.FollowWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadEditableWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowEditRightsUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowsUseCase
import run.ostrom.app.domain.usecases.workflows.SaveWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.WorkflowAccess
import run.ostrom.app.domain.workflowRun
import run.ostrom.app.fakeGuard

/** What a call of [WfcWorkflows.runs] was asked: account, workflow, status, cursor. */
internal data class WfcRunsCall(val accountId: String, val workflowId: String?, val status: String?, val before: String?)

/** A workflows server whose every answer a test sets (a lambda can suspend, or fail), recording the calls. */
internal class WfcWorkflows : WorkflowRepository {
    var listAnswer: suspend (String) -> List<Workflow> = { emptyList() }
    var oneAnswer: suspend (String, String) -> Workflow = { _, id -> aWorkflow(id, id) }
    var runsAnswer: suspend (WfcRunsCall) -> WorkflowRunPage = { WorkflowRunPage(emptyList(), null) }
    var runAnswer: suspend (String, String) -> WorkflowRun = { _, id -> workflowRun(id) }
    var startAnswer: suspend (String, Map<String, String>, String) -> WorkflowRun = { _, _, _ -> workflowRun("started", RunStatus.QUEUED) }
    var cancelAnswer: suspend (String, String) -> WorkflowRun = { _, id -> workflowRun(id, RunStatus.CANCELLED) }

    val listCalls = mutableListOf<String>()
    val runsCalls = mutableListOf<WfcRunsCall>()
    val runCalls = mutableListOf<String>()
    val startKeys = mutableListOf<String>()
    val cancelCalls = mutableListOf<String>()

    override suspend fun workflows(accountId: String): List<Workflow> { listCalls += accountId; return listAnswer(accountId) }
    override suspend fun workflow(accountId: String, workflowId: String): Workflow = oneAnswer(accountId, workflowId)
    override suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?): WorkflowRunPage {
        val call = WfcRunsCall(accountId, workflowId, status, before)
        runsCalls += call
        return runsAnswer(call)
    }
    override suspend fun run(accountId: String, runId: String): WorkflowRun { runCalls += runId; return runAnswer(accountId, runId) }
    override suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>, idempotencyKey: String): WorkflowRun {
        startKeys += idempotencyKey
        return startAnswer(workflowId, inputs, idempotencyKey)
    }
    override suspend fun cancel(accountId: String, runId: String): WorkflowRun { cancelCalls += runId; return cancelAnswer(accountId, runId) }
}

/** The editor's server, answers set by the test, calls recorded as `create <name>`, `save <id> v<version> <name>`, `delete <id>`. */
internal class WfcEditor : WorkflowEditorRepository {
    var rightsAnswer: suspend (String) -> WorkflowEditRights = { WorkflowEditRights(true, "all", 0) }
    var editableAnswer: suspend (String) -> EditableWorkflow = { id -> EditableWorkflow(id, 3, playedDraft()) }
    var createAnswer: suspend (WorkflowDraft) -> WorkflowWrite<String> = { WorkflowWrite.Applied("wf9") }
    var saveAnswer: suspend (EditableWorkflow, WorkflowDraft) -> WorkflowWrite<String> = { workflow, _ -> WorkflowWrite.Applied(workflow.id) }
    var deleteAnswer: suspend (String) -> WorkflowWrite<Unit> = { WorkflowWrite.Applied(Unit) }

    val calls = mutableListOf<String>()

    override suspend fun rights(accountId: String): WorkflowEditRights = rightsAnswer(accountId)
    override suspend fun editable(accountId: String, workflowId: String): EditableWorkflow { calls += "get $workflowId"; return editableAnswer(workflowId) }
    override suspend fun create(accountId: String, draft: WorkflowDraft): WorkflowWrite<String> { calls += "create ${draft.name}"; return createAnswer(draft) }
    override suspend fun save(accountId: String, workflow: EditableWorkflow, draft: WorkflowDraft): WorkflowWrite<String> {
        calls += "save ${workflow.id} v${workflow.version} ${draft.name}"
        return saveAnswer(workflow, draft)
    }
    override suspend fun delete(accountId: String, workflowId: String): WorkflowWrite<Unit> { calls += "delete $workflowId"; return deleteAnswer(workflowId) }
}

/** The use cases of the workflow view models over fakes: account `a1` signed in and active, the workflows feature served. */
internal class WfcHarness(served: Set<String> = setOf(Features.WORKFLOWS)) {
    val accounts = accounts().also { it.save(account()) }
    val auth = FakeAuthRepository()
    val workflows = WfcWorkflows()
    val editor = WfcEditor()
    val features = FakeFeatures(served)
    val events = FakeEventRepository()

    val access = WorkflowAccess(workflows, features, fakeGuard())
    val loadWorkflows = LoadWorkflowsUseCase(access)
    val getWorkflow = GetWorkflowUseCase(access)
    val loadRuns = LoadWorkflowRunsUseCase(access)
    val getRun = GetWorkflowRunUseCase(access)
    val cancelRun = CancelWorkflowRunUseCase(access)
    val loadRights = LoadWorkflowEditRightsUseCase(access, editor)
    val loadEditable = LoadEditableWorkflowUseCase(access, editor)
    val saveWorkflow = SaveWorkflowUseCase(access, editor)
    val deleteWorkflow = DeleteWorkflowUseCase(access, editor)
    val canManageRuns = CanManageRunsUseCase(auth, accounts)

    /** The feed of the runs, held by the test's background scope. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun follow(scope: TestScope): FollowWorkflowRunsUseCase {
        val feed = OrgFeed(events, features, CoroutineScope(scope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(scope.testScheduler)))
        return FollowWorkflowRunsUseCase(feed, features, fakeGuard())
    }
}
