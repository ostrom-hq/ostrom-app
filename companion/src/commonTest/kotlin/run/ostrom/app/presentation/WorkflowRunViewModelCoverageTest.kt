// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.visit
import run.ostrom.app.domain.workflowRun
import run.ostrom.app.presentation.navigation.WorkflowRunRoute
import run.ostrom.app.presentation.viewmodels.WorkflowRunUiState
import run.ostrom.app.presentation.viewmodels.WorkflowRunViewModel
import run.ostrom.app.presentation.viewmodels.userMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunViewModelCoverageTest {
    private val h = WfcHarness(setOf(Features.WORKFLOWS))
    private val wire = Channel<LiveUpdate<OrgChange>>(Channel.UNLIMITED)
    private val timeline = listOf(visit(1, status = "done", endedAt = 2_000L), visit(2, childRunId = "child"))

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(): WorkflowRunViewModel {
        h.events.changes = wire.receiveAsFlow()
        return WorkflowRunViewModel(WorkflowRunRoute("a1", "r1"), h.getRun, h.follow(this), h.cancelRun, h.canManageRuns)
    }

    private fun event(subject: String, type: String = OrgChange.RUN_UPDATED) = LiveUpdate.Item(OrgChange(type, subject, "m1", "wf1"))

    @Test fun cancelShowsOnARunThatHasNotEndedForWhoManagesRuns() {
        val running = workflowRun("r1")
        assertTrue(WorkflowRunUiState(managesRuns = true, run = running).canCancel)
        assertFalse(WorkflowRunUiState(managesRuns = false, run = running).canCancel)
        assertFalse(WorkflowRunUiState(managesRuns = true).canCancel)
        assertFalse(WorkflowRunUiState(managesRuns = true, run = workflowRun("r1", RunStatus.SUCCEEDED)).canCancel)
        assertFalse(WorkflowRunUiState(managesRuns = true, run = running, cancelling = true).canCancel)
    }

    @Test fun theRunIsReadWithTheRoleOfThePerson() = runTest {
        h.workflows.runAnswer = { _, id -> workflowRun(id, visits = timeline) }
        val model = model()
        assertEquals(timeline, model.uiState.value.run?.visits)
        assertFalse(model.uiState.value.loading)
        assertTrue(model.uiState.value.managesRuns)
        h.auth.organizations = listOf(Organization("o1", "Org", "member"))
        assertFalse(model().uiState.value.managesRuns)
    }

    @Test fun aRunGoneAndAFailedReadAreShown() = runTest {
        h.workflows.runAnswer = { _, _ -> throw AppException.NotFound() }
        val model = model()
        assertTrue(model.uiState.value.gone)
        assertEquals(AppException.NotFound().userMessage(), model.uiState.value.error)
        h.workflows.runAnswer = { _, _ -> throw AppException.Unreachable() }
        model.load()
        assertEquals(AppException.Unreachable().userMessage(), model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        h.workflows.runAnswer = { _, id -> workflowRun(id) }
        model.load()
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.gone)
        assertNotNull(model.uiState.value.run)
    }

    @Test fun startingTwiceFollowsOnceAndStoppingTwiceIsHarmless() = runTest {
        val model = model()
        assertEquals(1, h.workflows.runCalls.size)
        model.start()
        assertEquals(2, h.workflows.runCalls.size, "A server without the feed reads once at each start")
        model.start()
        assertEquals(3, h.workflows.runCalls.size)
        model.stop()
        model.stop()
        assertFalse(model.uiState.value.reconnecting)
    }

    @Test fun theFeedReadsTheRunAgainForItsOwnEventsAndItsSubWorkflowsOnly() = runTest {
        h.features.features = setOf(Features.WORKFLOWS, Features.EVENTS)
        h.workflows.runAnswer = { _, id -> workflowRun(id, visits = timeline) }
        val model = model()
        model.start()
        wire.send(LiveUpdate.Opened(resumed = false))
        assertEquals(2, h.workflows.runCalls.size)
        wire.send(event("other"))
        assertEquals(2, h.workflows.runCalls.size, "Another run is not this one")
        wire.send(event("r1", OrgChange.TODO_UPDATED))
        assertEquals(2, h.workflows.runCalls.size, "Nor another kind of event")
        wire.send(event("r1"))
        assertEquals(3, h.workflows.runCalls.size)
        wire.send(event("child"))
        assertEquals(4, h.workflows.runCalls.size, "A sub-workflow's run it shows")
        wire.send(LiveUpdate.Gap)
        assertEquals(5, h.workflows.runCalls.size)
        wire.send(LiveUpdate.Reconnecting(1_000))
        assertTrue(model.uiState.value.reconnecting)
        model.stop()
        assertFalse(model.uiState.value.reconnecting)
    }

    @Test fun aRunNotReadYetIsNotConcernedByAnotherRunsEvent() = runTest {
        h.features.features = setOf(Features.WORKFLOWS, Features.EVENTS)
        h.workflows.runAnswer = { _, _ -> throw AppException.NotFound() }
        val model = model()
        model.start()
        wire.send(LiveUpdate.Opened(resumed = false))
        assertEquals(2, h.workflows.runCalls.size)
        wire.send(event("child"))
        assertEquals(2, h.workflows.runCalls.size)
        wire.send(event("r1"))
        assertEquals(3, h.workflows.runCalls.size)
    }

    @Test fun aFeedThatFailsClearsTheReconnectingMark() = runTest {
        h.features.features = setOf(Features.WORKFLOWS, Features.EVENTS)
        val model = model()
        model.start()
        wire.send(LiveUpdate.Reconnecting(1_000))
        assertTrue(model.uiState.value.reconnecting)
        wire.close(AppException.Unreachable())
        assertFalse(model.uiState.value.reconnecting)
        assertNotNull(model.uiState.value.run, "What is shown stays")
        model.viewModelScope.cancel()
    }

    @Test fun cancelTakesTheStatusKeepsTheTimelineAndReadsTheRunAgain() = runTest {
        var gate: CompletableDeferred<WorkflowRun>? = null
        h.workflows.runAnswer = { _, id -> gate?.await() ?: workflowRun(id, visits = timeline) }
        val model = model()
        val reading = CompletableDeferred<WorkflowRun>().also { gate = it }
        model.cancel()
        assertEquals(listOf("r1"), h.workflows.cancelCalls)
        assertEquals(RunStatus.CANCELLED, model.uiState.value.run?.status)
        assertEquals(timeline, model.uiState.value.run?.visits, "The answer carries no visits: the timeline stays")
        assertFalse(model.uiState.value.cancelling)
        assertTrue(model.uiState.value.loading, "Then it is read again")
        assertFalse(model.uiState.value.canCancel)
        reading.complete(workflowRun("r1", RunStatus.CANCELLED, visits = timeline + visit(3, status = "failed", endedAt = 3_000L)))
        assertEquals(3, model.uiState.value.run?.visits?.size)
        assertFalse(model.uiState.value.loading)
    }

    @Test fun aRefusedCancelIsShownAndTheRunIsReadAgainAnyway() = runTest {
        val model = model()
        h.workflows.cancelAnswer = { _, _ -> throw AppException.Unreachable() }
        model.cancel()
        assertEquals(AppException.Unreachable().userMessage(), model.uiState.value.actionError)
        assertFalse(model.uiState.value.cancelling)
        assertEquals(2, h.workflows.runCalls.size)
        assertEquals(RunStatus.RUNNING, model.uiState.value.run?.status)
        h.workflows.cancelAnswer = { _, id -> workflowRun(id, RunStatus.CANCELLED) }
        model.cancel()
        assertNull(model.uiState.value.actionError)
    }

    @Test fun nothingIsCancelledWithoutTheRunOrTheRightToCancelIt() = runTest {
        h.workflows.runAnswer = { _, _ -> throw AppException.Unreachable() }
        val unread = model()
        unread.cancel()
        assertTrue(h.workflows.cancelCalls.isEmpty(), "No run read")
        h.workflows.runAnswer = { _, id -> workflowRun(id) }
        h.auth.organizations = listOf(Organization("o1", "Org", "member"))
        val member = model()
        member.cancel()
        assertTrue(h.workflows.cancelCalls.isEmpty(), "A member does not cancel runs")
        h.auth.organizations = emptyList()
        h.workflows.runAnswer = { _, id -> workflowRun(id, RunStatus.SUCCEEDED) }
        val ended = model()
        ended.cancel()
        assertTrue(h.workflows.cancelCalls.isEmpty(), "A run that ended is not cancelled")
    }
}
