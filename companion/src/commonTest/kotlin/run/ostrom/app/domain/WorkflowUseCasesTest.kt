// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/WorkflowUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.RunFilter
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.RunVisit
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.workflows.CanManageRunsUseCase
import run.ostrom.app.domain.usecases.workflows.CancelWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.FollowWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowsUseCase
import run.ostrom.app.domain.usecases.workflows.StartWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.WorkflowAccess
import run.ostrom.app.domain.usecases.workflows.concernsRun
import run.ostrom.app.domain.usecases.workflows.concernsRunsOf
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun aWorkflow(id: String, name: String) =
    Workflow(id, name, "engineering", null, 1, emptyList(), "accepted", null, emptyList(), emptyList(), null)

internal fun workflowRun(id: String, status: RunStatus = RunStatus.RUNNING, visits: List<RunVisit> = emptyList(), startedAt: Long? = 1_000L, endedAt: Long? = null) = WorkflowRun(
    id = id, workflowId = "wf1", workflowName = "Release", workflowVersion = 2, status = status, startedBy = "ada", source = "manual", inputs = emptyMap(),
    currentNodeId = "build", currentNodeKind = NodeKind.STEP, parentRunId = null, sessionId = null, repository = null, visits = visits, failure = null,
    failureCode = null, output = null, receiptId = null, startedAt = startedAt, endedAt = endedAt,
)

internal fun visit(seq: Int, status: String = "active", endedAt: Long? = null, childRunId: String? = null) =
    RunVisit(seq, "n$seq", "Node $seq", NodeKind.STEP, status, null, null, null, null, null, childRunId, 1_000L, endedAt)

class WorkflowUseCasesTest {
    private class FakeWorkflows : WorkflowRepository {
        val calls = mutableListOf<String>()
        val keys = mutableListOf<String>()
        override suspend fun workflows(accountId: String) = listOf(aWorkflow("w2", "release"), aWorkflow("w1", "Build"), aWorkflow("w3", "audit"))
        override suspend fun workflow(accountId: String, workflowId: String): Workflow { calls += "get $workflowId"; return aWorkflow(workflowId, "Build") }
        override suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?): WorkflowRunPage {
            calls += "runs ${workflowId ?: "mine"} ${status ?: "all"} ${before.orEmpty()}".trim()
            return WorkflowRunPage(listOf(workflowRun("r1")), null)
        }
        override suspend fun run(accountId: String, runId: String): WorkflowRun { calls += "run $runId"; return workflowRun(runId) }
        override suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>, idempotencyKey: String): WorkflowRun {
            calls += "start $workflowId $inputs"; keys += idempotencyKey; return workflowRun("new", RunStatus.QUEUED)
        }
        override suspend fun cancel(accountId: String, runId: String): WorkflowRun { calls += "cancel $runId"; return workflowRun(runId, RunStatus.CANCELLED) }
    }

    @Test fun everyCallIsRefusedByAServerWithoutWorkflows() = runTest {
        val repository = FakeWorkflows(); val features = FakeFeatures()
        val access = WorkflowAccess(repository, features, fakeGuard())
        assertFailsWith<AppException.Unsupported> { LoadWorkflowsUseCase(access)("a1") }
        assertFailsWith<AppException.Unsupported> { GetWorkflowUseCase(access)("a1", "w1") }
        assertFailsWith<AppException.Unsupported> { LoadWorkflowRunsUseCase(access)("a1", null, RunFilter.ALL) }
        assertFailsWith<AppException.Unsupported> { GetWorkflowRunUseCase(access)("a1", "r1") }
        assertFailsWith<AppException.Unsupported> { StartWorkflowRunUseCase(access)("a1", aWorkflow("w1", "Build"), emptyMap(), "k") }
        assertFailsWith<AppException.Unsupported> { CancelWorkflowRunUseCase(access)("a1", workflowRun("r1")) }
        assertEquals(emptyList(), repository.calls)
    }

    @Test fun theWorkflowsAreByNameAndTheRunsInTheirFilter() = runTest {
        val repository = FakeWorkflows(); val features = FakeFeatures(setOf(Features.WORKFLOWS))
        val access = WorkflowAccess(repository, features, fakeGuard())
        assertEquals(listOf("audit", "Build", "release"), LoadWorkflowsUseCase(access)("a1").map { it.name })
        GetWorkflowUseCase(access)("a1", "w1")
        LoadWorkflowRunsUseCase(access)("a1", null, RunFilter.ALL)
        LoadWorkflowRunsUseCase(access)("a1", "w1", RunFilter.DONE, "c2")
        LoadWorkflowRunsUseCase(access)("a1", null, RunFilter.CANCELLED)
        GetWorkflowRunUseCase(access)("a1", "r1")
        assertFailsWith<AppException.NotFound> { GetWorkflowRunUseCase(access)("a1", "") }
        assertFailsWith<AppException.NotFound> { GetWorkflowUseCase(access)("a1", "") }
        assertEquals(listOf("get w1", "runs mine all", "runs w1 succeeded c2", "runs mine cancelled", "run r1"), repository.calls)
    }

    @Test fun theRunsFollowTheRunEventsOfTheSharedFeed() = runTest {
        val features = FakeFeatures(setOf(Features.WORKFLOWS, Features.EVENTS, Features.WAITS))
        val change = OrgChange(OrgChange.RUN_UPDATED, "r1", "m1", "wf1")
        val events = FakeEventRepository(flowOf(LiveUpdate.Opened(false), LiveUpdate.Item(OrgChange(OrgChange.WAIT_CREATED, "w1", "m1")), LiveUpdate.Item(change), LiveUpdate.Gap))
        val signals = FollowWorkflowRunsUseCase(OrgFeed(events, features, backgroundScope), features, fakeGuard())("a1").toList()
        assertEquals(listOf(FeedSignal.Connection(false), FeedSignal.Reload, FeedSignal.Changed(change), FeedSignal.Reload), signals)
        features.features = setOf(Features.WORKFLOWS)
        assertEquals(listOf<FeedSignal>(FeedSignal.Reload), FollowWorkflowRunsUseCase(OrgFeed(events, features, backgroundScope), features, fakeGuard())("a1").toList())
        features.features = setOf(Features.EVENTS)
        assertEquals(emptyList(), FollowWorkflowRunsUseCase(OrgFeed(events, features, backgroundScope), features, fakeGuard())("a1").toList())
    }

    @Test fun aRunEventConcernsItsWorkflowsListsAndItsRunOrItsParent() {
        val change = OrgChange(OrgChange.RUN_UPDATED, "r2", "m1", "wf1")
        assertTrue(change.concernsRunsOf(null))
        assertTrue(change.concernsRunsOf("wf1"))
        assertFalse(change.concernsRunsOf("wf9"))
        assertTrue(change.copy(workflowId = null).concernsRunsOf("wf9"), "An event without its workflow loads every list again")
        assertFalse(OrgChange(OrgChange.TODO_UPDATED, "s1", "m1").concernsRunsOf(null))
        assertTrue(change.concernsRun(null, "r2"))
        assertFalse(change.concernsRun(null, "r1"))
        assertTrue(change.concernsRun(workflowRun("r1", visits = listOf(visit(1, childRunId = "r2"))), "r1"), "A sub-workflow's run moved")
    }

    @Test fun aRunSaysItsStepAndHowLongItRan() {
        val running = workflowRun("r1", visits = listOf(visit(1, "done", endedAt = 2_000L), visit(2)))
        assertEquals("Node 2", running.currentTitle)
        assertEquals(4_000L, running.durationMillis(5_000L))
        assertEquals("build", workflowRun("r1").currentTitle)
        val ended = workflowRun("r1", RunStatus.SUCCEEDED, startedAt = 1_000L, endedAt = 3_000L)
        assertNull(ended.currentTitle)
        assertEquals(2_000L, ended.durationMillis(9_000L))
        assertNull(workflowRun("r1", startedAt = null).durationMillis(9_000L))
        assertTrue(RunStatus.QUEUED.active); assertFalse(RunStatus.FAILED.active)
    }

    private val release = aWorkflow("w1", "Release").copy(
        inputs = listOf(
            WorkflowInput("version", "Version", required = true, default = null, maxLength = 10),
            WorkflowInput("notes", "Notes", required = false, default = null),
            WorkflowInput("channel", "Channel", required = true, default = "stable"),
        ),
    )

    @Test fun aRunStartsWithItsRequiredInputsTrimmedAndTheDefaultsFilledIn() = runTest {
        val repository = FakeWorkflows()
        val start = StartWorkflowRunUseCase(WorkflowAccess(repository, FakeFeatures(setOf(Features.WORKFLOWS)), fakeGuard()))
        assertEquals(RunStatus.QUEUED, start("a1", release, mapOf("version" to " 1.4.0 ", "notes" to "  "), "k1").status)
        assertEquals(listOf("start w1 {version=1.4.0, channel=stable}"), repository.calls, "An empty optional input is not sent; a default counts")
        val missing = assertFailsWith<AppException.WorkflowInputMissing> { start("a1", release, mapOf("version" to "  "), "k") }
        assertEquals("Version", missing.label)
        assertFailsWith<AppException.WorkflowInputMissing> { start("a1", release, mapOf("version" to "1", "channel" to ""), "k") }
        val long = assertFailsWith<AppException.WorkflowRefused> { start("a1", release, mapOf("version" to "12345678901"), "k") }
        assertEquals(WorkflowRefusal.INVALID_INPUTS, long.reason)
        assertEquals(1, repository.calls.size, "Nothing is sent before the inputs are right")
        assertEquals(listOf("k1"), repository.keys, "The caller's key goes with the start")
    }

    @Test fun onlyAnAcceptedWorkflowStarts() = runTest {
        val repository = FakeWorkflows()
        val start = StartWorkflowRunUseCase(WorkflowAccess(repository, FakeFeatures(setOf(Features.WORKFLOWS)), fakeGuard()))
        for (status in listOf("proposed", "rejected")) {
            val refused = assertFailsWith<AppException.WorkflowRefused> { start("a1", aWorkflow("w1", "Build").copy(status = status), emptyMap(), "k") }
            assertEquals(WorkflowRefusal.NOT_RUNNABLE, refused.reason)
        }
        start("a1", aWorkflow("w2", "Build").copy(status = "edited"), emptyMap(), "k")
        assertEquals(listOf("start w2 {}"), repository.calls)
    }

    @Test fun onlyARunThatHasNotEndedIsCancelled() = runTest {
        val repository = FakeWorkflows()
        val cancel = CancelWorkflowRunUseCase(WorkflowAccess(repository, FakeFeatures(setOf(Features.WORKFLOWS)), fakeGuard()))
        assertEquals(RunStatus.CANCELLED, cancel("a1", workflowRun("r1", RunStatus.WAITING)).status)
        for (status in listOf(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.UNKNOWN)) {
            assertEquals(WorkflowRefusal.RUN_ENDED, assertFailsWith<AppException.WorkflowRefused> { cancel("a1", workflowRun("r2", status)) }.reason)
        }
        assertEquals(listOf("cancel r1"), repository.calls)
    }

    @Test fun anOwnerOrAnAdminManagesRunsAndAnUnknownRoleShowsTheActions() = runTest {
        val auth = FakeAuthRepository()
        val accounts = accounts().also { it.save(account().copy(organizationId = "o2")) }
        val manages = CanManageRunsUseCase(auth, accounts)
        auth.organizations = listOf(Organization("o1", "One", "owner"), Organization("o2", "Two", "member"))
        assertFalse(manages("a1"), "A member of the account's organization")
        auth.organizations = listOf(Organization("o1", "One", "member"), Organization("o2", "Two", "admin"))
        assertTrue(manages("a1"))
        auth.organizations = listOf(Organization("o2", "Two", "approver"))
        assertFalse(manages("a1"))
        auth.organizations = listOf(Organization("o2", "Two"))
        assertTrue(manages("a1"), "A server that does not say lets the server refuse")
        auth.organizations = emptyList()
        assertTrue(manages("a1"))
        accounts.save(account().copy(organizationId = null))
        auth.organizations = listOf(Organization("o1", "One", "member"))
        assertFalse(manages("a1"), "The only organization is the account's")
        auth.organizationsError = AppException.Unreachable()
        assertTrue(manages("a1"), "Offline, the actions show and the server words a refusal")
        auth.organizationsError = AppException.Unauthorized()
        assertFalse(manages("a1"), "Signed out meanwhile")
        assertFalse(manages("unknown"))
        accounts.save(account(signedIn = false))
        assertFalse(manages("a1"))
    }
}
