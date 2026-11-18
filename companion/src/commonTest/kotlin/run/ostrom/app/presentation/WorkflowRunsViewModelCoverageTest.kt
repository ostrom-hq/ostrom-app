// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.RunFilter
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.workflowRun
import run.ostrom.app.presentation.navigation.WorkflowRoute
import run.ostrom.app.presentation.viewmodels.WorkflowRunsUiState
import run.ostrom.app.presentation.viewmodels.WorkflowRunsViewModel
import run.ostrom.app.presentation.viewmodels.userMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunsViewModelCoverageTest {
    private val h = WfcHarness(setOf(Features.WORKFLOWS))
    private val wire = Channel<LiveUpdate<OrgChange>>(Channel.UNLIMITED)

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(of: WorkflowRoute? = null): WorkflowRunsViewModel {
        h.events.changes = wire.receiveAsFlow()
        return WorkflowRunsViewModel(of, h.accounts, h.loadRuns, h.follow(this))
    }

    private fun feed() { h.features.features = setOf(Features.WORKFLOWS, Features.EVENTS) }

    private fun page(vararg ids: String, next: String? = null) = WorkflowRunPage(ids.map { workflowRun(it) }, next)

    private fun event(workflowId: String?, type: String = OrgChange.RUN_UPDATED) = LiveUpdate.Item(OrgChange(type, "r9", "m1", workflowId))

    @Test fun theEmptyListNeedsARunsListThatIsNotLoading() {
        assertFalse(WorkflowRunsUiState().empty)
        assertTrue(WorkflowRunsUiState(runs = emptyList()).empty)
        assertFalse(WorkflowRunsUiState(runs = emptyList(), loading = true).empty)
        assertFalse(WorkflowRunsUiState(runs = listOf(workflowRun("r1"))).empty)
    }

    @Test fun theRunsOfTheActiveAccountAreReadOnStartAndAGivenWorkflowsAreAsked() = runTest {
        h.workflows.runsAnswer = { page("r1", "r2", next = "c1") }
        val model = model()
        assertEquals("a1", model.uiState.value.accountId)
        assertTrue(h.workflows.runsCalls.isEmpty())
        model.start()
        assertEquals(listOf("r1", "r2"), model.uiState.value.runs?.map { it.id })
        assertEquals("c1", model.uiState.value.next)
        assertFalse(model.uiState.value.loading)
        assertEquals(listOf(WfcRunsCall("a1", null, null, null)), h.workflows.runsCalls)
        val ofWorkflow = model(WorkflowRoute("a1", "wf1"))
        ofWorkflow.start()
        assertEquals(WfcRunsCall("a1", "wf1", null, null), h.workflows.runsCalls.last())
    }

    @Test fun theRunsFollowTheSignedInActiveAccountAndKeepTheFilter() = runTest {
        val model = model()
        model.start()
        model.setFilter(RunFilter.DONE)
        assertEquals("succeeded", h.workflows.runsCalls.last().status)
        h.accounts.save(account(id = "a2"))
        assertEquals("a2", model.uiState.value.accountId)
        assertEquals(RunFilter.DONE, model.uiState.value.filter, "The chip stays on the other account")
        assertEquals("a2", h.workflows.runsCalls.last().accountId)
        // Signed out: no account to read for.
        h.accounts.update(account(id = "a2", signedIn = false))
        assertNull(model.uiState.value.accountId)
        val calls = h.workflows.runsCalls.size
        model.reload()
        model.loadMore()
        model.start()
        assertEquals(calls, h.workflows.runsCalls.size)
        assertFalse(model.uiState.value.loading)
    }

    @Test fun aFilterChangeReadsAgainAndTheSameFilterDoesNot() = runTest {
        val model = model()
        model.start()
        val calls = h.workflows.runsCalls.size
        model.setFilter(RunFilter.ALL)
        assertEquals(calls, h.workflows.runsCalls.size)
        model.setFilter(RunFilter.FAILED)
        assertEquals(calls + 1, h.workflows.runsCalls.size)
        assertEquals("failed", h.workflows.runsCalls.last().status)
        assertEquals(RunFilter.FAILED, model.uiState.value.filter)
    }

    @Test fun olderPagesAreAppendedOnceWithoutDoubles() = runTest {
        val gate = UncancellableGate<WorkflowRunPage>()
        h.workflows.runsAnswer = { call -> if (call.before == null) page("r1", "r2", next = "c1") else gate.await() }
        val model = model()
        model.loadMore()
        assertTrue(h.workflows.runsCalls.isEmpty(), "No cursor, nothing older")
        model.start()
        model.loadMore()
        assertTrue(model.uiState.value.loadingMore)
        model.loadMore()
        assertEquals(2, h.workflows.runsCalls.size, "A page already on its way is not asked twice")
        assertEquals("c1", h.workflows.runsCalls.last().before)
        gate.release(page("r2", "r3", next = "c2"))
        assertEquals(listOf("r1", "r2", "r3"), model.uiState.value.runs?.map { it.id })
        assertEquals("c2", model.uiState.value.next)
        assertFalse(model.uiState.value.loadingMore)
        h.workflows.runsAnswer = { page("r4") }
        model.loadMore()
        assertEquals(listOf("r1", "r2", "r3", "r4"), model.uiState.value.runs?.map { it.id })
        assertNull(model.uiState.value.next)
        model.loadMore()
        assertEquals(3, h.workflows.runsCalls.size)
    }

    @Test fun aFailedReadIsShownAndStopsTheSpinners() = runTest {
        h.workflows.runsAnswer = { call -> if (call.before == null) page("r1", next = "c1") else throw AppException.Unreachable() }
        val model = model()
        model.start()
        model.loadMore()
        assertEquals(AppException.Unreachable().userMessage(), model.uiState.value.error)
        assertFalse(model.uiState.value.loadingMore)
        assertEquals(listOf("r1"), model.uiState.value.runs?.map { it.id }, "What was read stays")
        h.workflows.runsAnswer = { throw AppException.Unreachable() }
        model.reload()
        assertFalse(model.uiState.value.loading)
        assertEquals(AppException.Unreachable().userMessage(), model.uiState.value.error)
    }

    @Test fun aPageThatLandsAfterTheFilterOrTheAccountChangedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<WorkflowRunPage>>()
        h.workflows.runsAnswer = { UncancellableGate<WorkflowRunPage>().also { gates.addLast(it) }.await() }
        val model = model()
        model.start()
        val stale = gates.removeFirst()
        model.setFilter(RunFilter.DONE)
        val current = gates.removeFirst()
        stale.release(page("stale"))
        assertNull(model.uiState.value.runs)
        assertTrue(model.uiState.value.loading)
        current.release(page("done"))
        assertEquals(listOf("done"), model.uiState.value.runs?.map { it.id })
        // The account changes while a page and then a failure are on their way.
        for (outcome in 0..1) {
            h.accounts.setActive("a1")
            gates.clear()
            model.reload()
            val inFlight = gates.removeFirst()
            h.accounts.save(account(id = "b$outcome"))
            gates.clear()
            if (outcome == 0) inFlight.release(page("late")) else inFlight.fail(AppException.Unreachable())
            assertEquals("b$outcome", model.uiState.value.accountId)
            assertNull(model.uiState.value.runs)
            assertNull(model.uiState.value.error)
        }
    }

    @Test fun anOlderPageLandingOnAListThatWasResetStartsTheList() = runTest {
        val gates = ArrayDeque<UncancellableGate<WorkflowRunPage>>()
        h.workflows.runsAnswer = { UncancellableGate<WorkflowRunPage>().also { gates.addLast(it) }.await() }
        val model = model()
        model.start()
        gates.removeFirst().release(page("r1", next = "c1"))
        model.loadMore()
        val older = gates.removeFirst()
        // The person goes to another account and back while the older page is on its way: the list starts again.
        h.accounts.save(account(id = "a2"))
        val other = gates.removeFirst()
        h.accounts.setActive("a1")
        val again = gates.removeFirst()
        assertNull(model.uiState.value.runs)
        older.release(page("r0"))
        assertEquals(listOf("r0"), model.uiState.value.runs?.map { it.id })
        other.release(page("x"))
        again.release(page("r1", next = "c1"))
        assertEquals(listOf("r1"), model.uiState.value.runs?.map { it.id })
        assertEquals("c1", model.uiState.value.next)
    }

    @Test fun theFeedReloadsAtOpenAndGapAndShowsTheConnection() = runTest {
        feed()
        val model = model()
        model.start()
        wire.send(LiveUpdate.Opened(resumed = false))
        assertEquals(1, h.workflows.runsCalls.size)
        wire.send(LiveUpdate.Reconnecting(1_000))
        assertTrue(model.uiState.value.reconnecting)
        wire.send(LiveUpdate.Opened(resumed = true))
        assertFalse(model.uiState.value.reconnecting)
        assertEquals(1, h.workflows.runsCalls.size)
        wire.send(LiveUpdate.Gap)
        assertEquals(2, h.workflows.runsCalls.size)
        // An event of another type is not the list's.
        wire.send(event("wf1", OrgChange.TODO_UPDATED))
        advanceTimeBy(1_000)
        assertEquals(2, h.workflows.runsCalls.size)
    }

    @Test fun aBurstOfRunEventsReadsTheListOnce() = runTest {
        feed()
        val model = model()
        model.start()
        wire.send(LiveUpdate.Opened(resumed = false))
        assertEquals(1, h.workflows.runsCalls.size)
        wire.send(event("wf1")); wire.send(event("wf2")); wire.send(event(null))
        advanceTimeBy(100)
        assertEquals(1, h.workflows.runsCalls.size, "Waits for the rest of the burst")
        advanceTimeBy(400)
        assertEquals(2, h.workflows.runsCalls.size)
        wire.send(event("wf1"))
        advanceTimeBy(400)
        assertEquals(3, h.workflows.runsCalls.size, "A later event starts a new wait")
    }

    @Test fun aWorkflowsRunsListReadsOnlyForItsOwnEvents() = runTest {
        feed()
        val model = model(WorkflowRoute("a1", "wf1"))
        model.start()
        wire.send(LiveUpdate.Opened(resumed = false))
        assertEquals(1, h.workflows.runsCalls.size)
        wire.send(event("other"))
        advanceTimeBy(400)
        assertEquals(1, h.workflows.runsCalls.size)
        wire.send(event("wf1"))
        advanceTimeBy(400)
        assertEquals(2, h.workflows.runsCalls.size)
        wire.send(event(null))
        advanceTimeBy(400)
        assertEquals(3, h.workflows.runsCalls.size, "An event that names no workflow may concern it")
    }

    @Test fun stoppingEndsTheFollowAndAPendingReadAndTheFeedFailureClearsTheMark() = runTest {
        feed()
        val model = model()
        model.start()
        wire.send(LiveUpdate.Opened(resumed = false))
        wire.send(event("wf1"))
        model.stop()
        advanceTimeBy(400)
        assertEquals(1, h.workflows.runsCalls.size, "The read waiting for the burst is dropped")
        model.stop()
        assertFalse(model.uiState.value.reconnecting)
        wire.send(LiveUpdate.Reconnecting(1_000))
        runCurrent()
        assertFalse(model.uiState.value.reconnecting, "Nothing follows after a stop")
        // Followed again: the feed fails and what is shown stays.
        model.start()
        wire.send(LiveUpdate.Reconnecting(1_000))
        assertTrue(model.uiState.value.reconnecting)
        wire.close(AppException.Unreachable())
        assertFalse(model.uiState.value.reconnecting)
        model.viewModelScope.cancel()
    }
}
