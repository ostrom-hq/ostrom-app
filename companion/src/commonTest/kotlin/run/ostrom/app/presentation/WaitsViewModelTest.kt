// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.FieldInput
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitField
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.waits.AnswerWaitUseCase
import run.ostrom.app.domain.usecases.waits.FollowWaitsUseCase
import run.ostrom.app.domain.usecases.waits.LoadInProgressWaitsUseCase
import run.ostrom.app.domain.usecases.waits.LoadWaitsUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.viewmodels.WaitFilter
import run.ostrom.app.presentation.viewmodels.WaitNotice
import run.ostrom.app.presentation.viewmodels.WaitsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun wait(id: String, fields: List<WaitField> = emptyList(), actions: List<WaitAction> = listOf(WaitAction("done", "Done"))) =
    Wait(id, WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", "Fix the login", null, "m1", "Review the plan", null, emptyList(), fields, actions, 1)

/** The "Waiting for you" model: the account it follows, the lists and their pages, the answers, and the live feed. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Waits : WaitRepository {
        val calls = mutableListOf<String>()
        var onList: suspend (WaitStatus, String?) -> WaitPage = { _, _ -> WaitPage(emptyList(), null) }
        var onAnswer: suspend (String, String) -> Wait = { id, _ -> wait(id).copy(open = false) }
        var onProgress: suspend () -> List<Wait> = { emptyList() }

        override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage {
            calls += "list ${status.name} ${before.orEmpty()}".trim()
            return onList(status, before)
        }

        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
            calls += "answer $waitId $action"
            return onAnswer(waitId, action)
        }

        override suspend fun inProgress(accountId: String): List<Wait> {
            calls += "inProgress"
            return onProgress()
        }
    }

    private val waits = Waits()
    private val features = FakeFeatures(setOf(Features.WAITS))
    private val shared = MutableSharedFlow<LiveUpdate<OrgChange>>(replay = 16)
    private val events = FakeEventRepository(shared)

    private fun signedIn(): AccountRepository = accounts().also { it.save(account("a1")) }

    private fun TestScope.vm(repository: AccountRepository = signedIn()): WaitsViewModel {
        val guard = fakeGuard()
        return WaitsViewModel(
            repository, LoadWaitsUseCase(waits, features, guard), AnswerWaitUseCase(waits, guard),
            FollowWaitsUseCase(OrgFeed(events, features, backgroundScope), features, guard), LoadInProgressWaitsUseCase(waits, features, guard),
        )
    }

    private fun TestScope.settle() = runCurrent()

    private fun opens() = waits.calls.count { it == "list OPEN" }

    private val WaitsViewModel.st get() = uiState.value

    /** A started model on a server with waits, whose first page is [first] and whose pages come from [pages]. */
    private fun TestScope.started(vararg first: Wait, next: String? = null): WaitsViewModel {
        waits.onList = { _, before -> if (before == null) WaitPage(first.toList(), next) else WaitPage(emptyList(), null) }
        val model = vm(); settle()
        model.start(); settle()
        return model
    }

    @Test fun theStateFollowsTheActiveAccountAndOnlyWhatIdentifiesItResetsIt() = runTest(dispatcher) {
        val repository = accounts()
        waits.onList = { _, _ -> WaitPage(listOf(wait("w1")), null) }
        val model = vm(repository); settle()
        assertNull(model.st.account)
        model.start(); settle()
        assertTrue(waits.calls.isEmpty(), "no account, nothing to load")
        repository.save(account("a1")); settle()
        assertEquals("a1", model.st.account?.id)
        assertEquals(1, opens())
        assertEquals(listOf("w1"), model.st.waits.map { it.id })
        assertTrue(model.st.available && model.st.loaded)
        assertEquals(1, model.st.openCount)
        // A new name does not make it another account: what is shown stays, and nothing is loaded again.
        repository.update(account("a1").copy(name = "Ada L.")); settle()
        assertEquals(1, opens())
        assertEquals(listOf("w1"), model.st.waits.map { it.id })
        repository.update(account("a1", login = "grace")); settle()
        assertEquals(2, opens())
        repository.update(account("a1", login = "grace", origin = "https://other.example.org")); settle()
        assertEquals(3, opens())
        // Signed out: what was shown is dropped and nothing is followed.
        repository.update(account("a1", login = "grace", origin = "https://other.example.org", signedIn = false)); settle()
        assertEquals(3, opens())
        assertTrue(model.st.waits.isEmpty()); assertFalse(model.st.loaded)
        repository.update(account("a1", login = "grace", origin = "https://other.example.org")); settle()
        assertEquals(4, opens())
        repository.save(account("b1")); settle()
        assertEquals("b1", model.st.account?.id)
        assertEquals(5, opens())
        repository.remove("b1"); settle()
        assertEquals("a1", model.st.account?.id)
        repository.remove("a1"); settle()
        assertNull(model.st.account)
        assertTrue(model.st.waits.isEmpty())
    }

    @Test fun eachOutcomeOfAnAnswerIsShownOnItsWait() = runTest(dispatcher) {
        val needsFeedback = WaitField("feedback", FieldType.TEXT, "Feedback", required = true)
        val all = listOf(wait("w1"), wait("w2", fields = listOf(needsFeedback)), wait("w3"), wait("w4"), wait("w5"), wait("w6"))
        val model = started(*all.toTypedArray())
        assertEquals(6, model.st.openCount)
        model.answer(all[0], "done"); settle()
        assertEquals(listOf("w2", "w3", "w4", "w5", "w6"), model.st.waits.map { it.id })
        assertFalse(model.st.closedElsewhere)
        // A field to fix, found before anything is sent.
        model.answer(all[1], "done"); settle()
        val problem = model.st.drafts["w2"]
        assertEquals("feedback", problem?.problem?.fieldId)
        assertFalse(problem!!.sending); assertNull(problem.sendingAction)
        assertTrue(waits.calls.none { it.startsWith("answer w2") })
        model.input("w2", "feedback", FieldInput.Text("fine"))
        assertNull(model.st.drafts["w2"]?.problem)
        assertEquals(FieldInput.Text("fine"), model.st.drafts["w2"]?.inputs?.get("feedback"))
        // An action the wait does not have.
        model.answer(all[2], "nope"); settle()
        assertEquals(WaitNotice.REFUSED, model.st.drafts["w3"]?.notice)
        assertFalse(model.st.drafts["w3"]!!.sending); assertNull(model.st.drafts["w3"]?.sendingAction)
        // Offline: the same answer can be sent again.
        waits.onAnswer = { _, _ -> throw AppException.Unreachable() }
        model.answer(all[2], "done"); settle()
        assertEquals(WaitNotice.RETRY, model.st.drafts["w3"]?.notice)
        assertFalse(model.st.drafts["w3"]!!.sending)
        // Any other failure.
        waits.onAnswer = { _, _ -> throw IllegalStateException("boom") }
        model.answer(all[2], "done"); settle()
        assertEquals(WaitNotice.FAILED, model.st.drafts["w3"]?.notice)
        // Someone answered it first: it leaves the list, said once.
        waits.onAnswer = { _, _ -> throw AppException.Server(409, "wait_closed") }
        model.answer(all[3], "done"); settle()
        assertTrue("w4" !in model.st.waits.map { it.id })
        assertTrue(model.st.closedElsewhere)
        model.dismissClosed()
        assertFalse(model.st.closedElsewhere)
        model.answer(all[4], "done"); settle()
        assertTrue(model.st.closedElsewhere)
        model.input("w3", "f", FieldInput.Text("a"))
        assertFalse(model.st.closedElsewhere, "the next action clears it")
        // The answer is cancelled on its way: the button is released and the wait stays.
        waits.onAnswer = { _, _ -> throw CancellationException("cancelled") }
        model.answer(all[5], "done"); settle()
        assertFalse(model.st.drafts["w6"]!!.sending); assertNull(model.st.drafts["w6"]?.sendingAction)
        assertTrue("w6" in model.st.waits.map { it.id })
    }

    @Test fun aSecondAnswerWaitsForTheFirstAndAWaitNoLongerListedGetsNoDraft() = runTest(dispatcher) {
        val model = started(wait("w1"))
        val gate = CompletableDeferred<Unit>()
        waits.onAnswer = { id, _ -> gate.await(); wait(id).copy(open = false) }
        model.answer(wait("w1"), "done"); settle()
        assertTrue(model.st.drafts["w1"]!!.sending)
        assertEquals("done", model.st.drafts["w1"]?.sendingAction)
        model.answer(wait("w1"), "done"); settle()
        assertEquals(1, waits.calls.count { it == "answer w1 done" })
        gate.complete(Unit); settle()
        assertTrue(model.st.waits.isEmpty())
        assertTrue(model.st.drafts.isEmpty())
        // A wait that is not in the list (another account, a reload that dropped it): its answer leaves no draft behind.
        waits.onAnswer = { id, _ -> wait(id).copy(open = false) }
        model.answer(wait("ghost"), "done"); settle()
        assertTrue(model.st.drafts.isEmpty())
    }

    @Test fun withoutAnAccountNothingIsAnsweredFilteredOrLoaded() = runTest(dispatcher) {
        val model = vm(accounts()); settle()
        model.start(); settle()
        model.answer(wait("w1"), "done"); model.setFilter(WaitFilter.ANSWERED); model.loadMore(); model.retry(); settle()
        assertTrue(waits.calls.isEmpty())
        assertEquals(WaitFilter.OPEN, model.st.filter)
        model.stop()
        assertFalse(model.st.reconnecting)
    }

    @Test fun theNextPageOfTheShownListIsAddedWithoutDuplicates() = runTest(dispatcher) {
        waits.onList = { status, before ->
            when (status to before) {
                WaitStatus.OPEN to null -> WaitPage(listOf(wait("w1"), wait("w2")), "n1")
                WaitStatus.OPEN to "n1" -> WaitPage(listOf(wait("w2"), wait("w3")), null)
                WaitStatus.RESOLVED to null -> WaitPage(listOf(wait("h1")), "hn")
                WaitStatus.RESOLVED to "hn" -> WaitPage(listOf(wait("h1"), wait("h2")), null)
                else -> WaitPage(listOf(wait("c1")), null)
            }
        }
        val model = vm(); settle()
        model.start(); settle()
        assertEquals(listOf("w1", "w2"), model.st.waits.map { it.id })
        assertTrue(model.st.moreOpen)
        model.loadMore(); settle()
        assertEquals(listOf("w1", "w2", "w3"), model.st.waits.map { it.id })
        assertFalse(model.st.moreOpen); assertFalse(model.st.loadingMore); assertFalse(model.st.error)
        val asked = waits.calls.size
        model.loadMore(); settle()
        assertEquals(asked, waits.calls.size, "the last page is there: nothing to ask")
        // The answered list is read from its first page; the open waits stay as they are.
        model.setFilter(WaitFilter.ANSWERED); settle()
        assertEquals(listOf("h1"), model.st.history.map { it.id })
        assertEquals(listOf("h1"), model.st.shown.map { it.id })
        assertEquals("hn", model.st.shownNext)
        assertTrue(model.st.shownLoaded)
        assertEquals(3, model.st.waits.size)
        model.loadMore(); settle()
        assertEquals(listOf("h1", "h2"), model.st.history.map { it.id })
        assertNull(model.st.historyNext)
        val before = waits.calls.size
        model.setFilter(WaitFilter.ANSWERED); settle()
        assertEquals(before, waits.calls.size, "the same list again changes nothing")
        model.setFilter(WaitFilter.CANCELLED); settle()
        assertEquals(listOf("c1"), model.st.history.map { it.id })
        val reads = waits.calls.size
        model.setFilter(WaitFilter.OPEN); settle()
        assertEquals(reads, waits.calls.size, "the open waits are live: nothing is read")
        assertTrue(model.st.history.isEmpty())
        assertEquals(listOf("w1", "w2", "w3"), model.st.shown.map { it.id })
        assertNull(model.st.shownNext)
    }

    @Test fun aFilterChosenBeforeAnyPageWasLoadedStartsFromItsFirstPage() = runTest(dispatcher) {
        val model = started(wait("w1"))
        waits.onList = { _, _ -> WaitPage(listOf(wait("h1")), null) }
        model.setFilter(WaitFilter.ANSWERED); settle()
        assertEquals(listOf("h1"), model.st.history.map { it.id })
        assertTrue(model.st.historyLoaded)
    }

    @Test fun aPageThatFailsKeepsTheListAndRetryLoadsAgain() = runTest(dispatcher) {
        var failing = true
        waits.onList = { _, before ->
            when {
                before == null -> WaitPage(listOf(wait("w1")), "n1")
                failing -> throw AppException.Unreachable()
                else -> WaitPage(listOf(wait("w2")), null)
            }
        }
        val model = vm(); settle()
        model.start(); settle()
        model.loadMore(); settle()
        assertTrue(model.st.error); assertFalse(model.st.loadingMore)
        assertEquals(listOf("w1"), model.st.waits.map { it.id })
        failing = false
        model.retry(); settle()
        assertFalse(model.st.error)
        assertEquals(listOf("w1"), model.st.waits.map { it.id })
        model.loadMore(); settle()
        assertEquals(listOf("w1", "w2"), model.st.waits.map { it.id })
    }

    @Test fun aPageThatArrivesAfterTheListWasReloadedIsNotAdded() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var first = "n1"
        waits.onList = { _, before -> if (before == null) WaitPage(listOf(wait("w1")), first) else { gate.await(); WaitPage(listOf(wait("w9")), null) } }
        val model = vm(); settle()
        model.start(); settle()
        model.loadMore(); settle()
        assertTrue(model.st.loadingMore)
        model.loadMore(); settle()
        assertEquals(1, waits.calls.count { it == "list OPEN n1" }, "one page at a time")
        // The list is loaded again meanwhile (its cursor moved): the late page follows nothing.
        first = "n2"
        model.retry(); settle()
        gate.complete(Unit); settle()
        assertFalse(model.st.loadingMore)
        assertEquals(listOf("w1"), model.st.waits.map { it.id })
        assertEquals("n2", model.st.nextBefore)
    }

    @Test fun switchingAccountDropsThePageInFlight() = runTest(dispatcher) {
        val repository = signedIn()
        val gate = CompletableDeferred<Unit>()
        waits.onList = { _, before -> if (before == null) WaitPage(listOf(wait("w1")), "n1") else { gate.await(); WaitPage(listOf(wait("w9")), null) } }
        val model = vm(repository); settle()
        model.start(); settle()
        model.loadMore(); settle()
        assertTrue(model.st.loadingMore)
        repository.save(account("b1")); settle()
        assertEquals("b1", model.st.account?.id)
        assertFalse(model.st.loadingMore)
        gate.complete(Unit); settle()
        assertEquals(listOf("w1"), model.st.waits.map { it.id })
    }

    @Test fun aLoadThatFailsKeepsTheStateAndMarksTheError() = runTest(dispatcher) {
        waits.onList = { _, _ -> throw AppException.Unreachable() }
        val model = vm(); settle()
        model.start(); settle()
        assertTrue(model.st.error)
        assertFalse(model.st.available); assertFalse(model.st.loaded)
        waits.onList = { _, _ -> WaitPage(listOf(wait("w1")), null) }
        model.retry(); settle()
        assertFalse(model.st.error)
        assertTrue(model.st.available && model.st.loaded)
    }

    @Test fun aSignedOutAnswerEndsTheFollowAndRetryShowsTheError() = runTest(dispatcher) {
        waits.onList = { _, _ -> throw AppException.Unauthorized() }
        val model = vm(); settle()
        model.start(); settle()
        assertFalse(model.st.reconnecting)
        assertFalse(model.st.error, "the follow ended by itself: what is shown stays")
        model.retry(); settle()
        assertTrue(model.st.error)
    }

    @Test fun aServerWithoutWaitsHidesThemAndAReloadDropsWhatWasKept() = runTest(dispatcher) {
        val model = started(wait("w1"), wait("w2"))
        waits.onProgress = { listOf(wait("p1")) }
        model.retry(); settle()
        assertEquals(listOf("p1"), model.st.inProgress.map { it.id })
        model.input("w1", "f", FieldInput.Text("typed"))
        // Another page of waits without w1: its draft goes, the others keep what was typed.
        model.input("w2", "f", FieldInput.Text("kept"))
        waits.onList = { _, _ -> WaitPage(listOf(wait("w2")), null) }
        model.retry(); settle()
        assertEquals(setOf("w2"), model.st.drafts.keys)
        features.features = emptySet()
        model.retry(); settle()
        assertFalse(model.st.available)
        assertTrue(model.st.waits.isEmpty()); assertTrue(model.st.inProgress.isEmpty()); assertTrue(model.st.loaded)
    }

    @Test fun whatTheServerWatchesIsKeptWhenItsReadFailsAndTheFeedEndsOnASignOut() = runTest(dispatcher) {
        val model = started(wait("w1"))
        waits.onProgress = { listOf(wait("p1")) }
        model.retry(); settle()
        waits.onProgress = { throw AppException.Unreachable() }
        model.retry(); settle()
        assertEquals(listOf("p1"), model.st.inProgress.map { it.id }, "the section keeps what it had")
        assertFalse(model.st.error)
        waits.onProgress = { throw AppException.Unauthorized() }
        model.retry(); settle()
        assertTrue(model.st.error)
    }

    @Test fun aReloadUnderAnotherFilterReadsItsListAgain() = runTest(dispatcher) {
        waits.onList = { status, _ -> if (status == WaitStatus.OPEN) WaitPage(listOf(wait("w1")), null) else WaitPage(listOf(wait("h1")), null) }
        val model = vm(); settle()
        model.start(); settle()
        model.setFilter(WaitFilter.ANSWERED); settle()
        assertEquals(1, waits.calls.count { it == "list RESOLVED" })
        model.retry(); settle()
        assertEquals(2, waits.calls.count { it == "list RESOLVED" })
        assertEquals(listOf("h1"), model.st.history.map { it.id })
        assertFalse(model.st.error)
    }

    @Test fun aHistoryThatFailsMarksTheErrorOnlyForTheFilterStillShown() = runTest(dispatcher) {
        val slow = CompletableDeferred<Unit>()
        waits.onList = { status, _ ->
            when (status) {
                WaitStatus.OPEN -> WaitPage(listOf(wait("w1")), null)
                WaitStatus.RESOLVED -> { slow.await(); throw AppException.Unreachable() }
                else -> WaitPage(listOf(wait("c1")), null)
            }
        }
        val model = vm(); settle()
        model.start(); settle()
        // The answered list answers after the member moved on to the withdrawn one: its failure is not theirs.
        model.setFilter(WaitFilter.ANSWERED); settle()
        model.setFilter(WaitFilter.CANCELLED); settle()
        slow.complete(Unit); settle()
        assertFalse(model.st.error)
        assertEquals(listOf("c1"), model.st.history.map { it.id })
        // Still on it: the failure shows.
        waits.onList = { status, _ -> if (status == WaitStatus.OPEN) WaitPage(listOf(wait("w1")), null) else throw AppException.Unreachable() }
        model.setFilter(WaitFilter.ANSWERED); settle()
        assertTrue(model.st.error)
        assertFalse(model.st.historyLoaded)
    }

    @Test fun aHistoryThatArrivesAfterAnotherFilterWasChosenIsDropped() = runTest(dispatcher) {
        val slow = CompletableDeferred<Unit>()
        waits.onList = { status, _ ->
            when (status) {
                WaitStatus.OPEN -> WaitPage(listOf(wait("w1")), null)
                WaitStatus.RESOLVED -> { slow.await(); WaitPage(listOf(wait("h1")), null) }
                else -> WaitPage(listOf(wait("c1")), null)
            }
        }
        val model = vm(); settle()
        model.start(); settle()
        model.setFilter(WaitFilter.ANSWERED); settle()
        model.setFilter(WaitFilter.CANCELLED); settle()
        slow.complete(Unit); settle()
        assertEquals(listOf("c1"), model.st.history.map { it.id })
    }

    @Test fun aHistoryFromAServerThatLostWaitsHidesThem() = runTest(dispatcher) {
        val model = started(wait("w1"))
        features.features = emptySet()
        model.setFilter(WaitFilter.ANSWERED); settle()
        assertFalse(model.st.available)
    }

    @Test fun theConnectionStateAndTheFeedsSignalsAreFollowed() = runTest(dispatcher) {
        features.features = setOf(Features.WAITS, Features.EVENTS)
        waits.onList = { _, _ -> WaitPage(listOf(wait("w1")), null) }
        shared.tryEmit(LiveUpdate.Opened(resumed = false))
        val model = vm(); settle()
        model.start(); settle()
        assertEquals(1, opens(), "a load once the feed is open")
        assertFalse(model.st.reconnecting)
        shared.tryEmit(LiveUpdate.Reconnecting(1_000)); settle()
        assertTrue(model.st.reconnecting)
        shared.tryEmit(LiveUpdate.Opened(resumed = true)); settle()
        assertFalse(model.st.reconnecting)
        assertEquals(1, opens(), "a connection that resumed loads nothing")
        shared.tryEmit(LiveUpdate.Gap); settle()
        assertEquals(2, opens())
        shared.tryEmit(LiveUpdate.Reconnecting(1_000)); settle()
        assertTrue(model.st.reconnecting)
        model.stop(); settle()
        assertFalse(model.st.reconnecting)
        shared.tryEmit(LiveUpdate.Gap); settle()
        assertEquals(2, opens(), "stopped: no longer followed")
    }

    @Test fun aFeedThatEndsWithAFailureLeavesWhatIsShownAndNotReconnecting() = runTest(dispatcher) {
        features.features = setOf(Features.WAITS, Features.EVENTS)
        waits.onList = { _, _ -> WaitPage(listOf(wait("w1")), null) }
        events.changes = flow {
            emit(LiveUpdate.Opened(resumed = false))
            emit(LiveUpdate.Reconnecting(1_000))
            throw AppException.Unreachable()
        }
        val model = vm(); settle()
        model.start(); settle()
        assertFalse(model.st.reconnecting, "the follow ended: nothing is reconnecting any more")
        assertEquals(listOf("w1"), model.st.waits.map { it.id })
    }
}
