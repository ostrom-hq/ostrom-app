// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CoroutineScope
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
import androidx.lifecycle.viewModelScope
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.SessionFilter
import run.ostrom.app.domain.models.SessionPage
import run.ostrom.app.domain.repositories.SessionRepository
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.sessions.FollowSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadSessionsUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.domain.session
import run.ostrom.app.presentation.viewmodels.SessionsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val accounts = accounts().also { it.save(account()) }
    private val features = FakeFeatures(setOf(Features.SESSIONS))
    private val events = FakeEventRepository()
    private val repo = Repo()

    private class Repo : SessionRepository {
        val calls = mutableListOf<String>()
        var list: suspend (Boolean?, String?) -> SessionPage = { _, _ -> SessionPage(emptyList(), null) }
        override suspend fun sessions(accountId: String, open: Boolean?, before: String?): SessionPage {
            calls += "$accountId ${open ?: "all"} ${before.orEmpty()}".trim()
            return list(open, before)
        }
        override suspend fun session(accountId: String, sessionId: String) = run.ostrom.app.domain.session(sessionId)
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(): SessionsViewModel {
        val feed = OrgFeed(events, features, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        return SessionsViewModel(accounts, LoadSessionsUseCase(repo, features, fakeGuard()), FollowSessionsUseCase(feed, features, fakeGuard()))
    }

    private fun row(id: String, startedAt: Long?) = session(id).copy(startedAt = startedAt)

    @Test
    fun theFirstPageLoadsOnStartAndAnotherFilterReadsAgain() = runTest {
        repo.list = { _, _ -> SessionPage(listOf(row("n1", 5_000)), null) }
        val model = model()
        assertTrue(repo.calls.isEmpty())
        model.reload()
        assertEquals(listOf("a1 all"), repo.calls)
        model.start()
        assertEquals(listOf("a1 all", "a1 all"), repo.calls)
        assertEquals(listOf("n1"), model.uiState.value.sessions.map { it.id })
        assertTrue(model.uiState.value.loaded)
        model.setFilter(SessionFilter.ALL)
        assertEquals(2, repo.calls.size)
        model.setFilter(SessionFilter.OPEN)
        assertEquals("a1 true", repo.calls.last())
        assertEquals(SessionFilter.OPEN, model.uiState.value.filter)
        model.stop()
        assertFalse(model.uiState.value.reconnecting)
    }

    @Test
    fun olderPagesAreAppendedOnceAndNeverDoubled() = runTest {
        val gate = UncancellableGate<SessionPage>()
        repo.list = { _, before -> if (before == null) SessionPage(listOf(row("n1", 5_000)), "c2") else gate.await() }
        val model = model()
        model.loadMore()
        assertTrue(repo.calls.isEmpty())
        model.reload()
        model.loadMore()
        assertTrue(model.uiState.value.loadingMore)
        // A second request while this one runs is not made.
        model.loadMore()
        assertEquals(listOf("a1 all", "a1 all c2"), repo.calls)
        gate.release(SessionPage(listOf(row("n1", 5_000), row("n0", 4_000)), null))
        assertEquals(listOf("n1", "n0"), model.uiState.value.sessions.map { it.id })
        assertNull(model.uiState.value.next)
        assertFalse(model.uiState.value.loadingMore)
        model.loadMore()
        assertEquals(2, repo.calls.size)
    }

    @Test
    fun aRefreshKeepsTheOlderRowsAlreadyRead() = runTest {
        var first: SessionPage = SessionPage(listOf(row("a", 5_000), row("b", 4_000)), "c2")
        repo.list = { _, before -> if (before == null) first else SessionPage(listOf(row("c", 3_000), row("d", null)), "c3") }
        val model = model()
        model.reload()
        model.loadMore()
        assertEquals(listOf("a", "b", "c", "d"), model.uiState.value.sessions.map { it.id })
        assertEquals("c3", model.uiState.value.next)
        // The first page moved: the rows older than its last one stay after it, and the cursor stays where it was.
        first = SessionPage(listOf(row("a2", 6_000), row("b", 4_000)), "c2")
        model.reload()
        assertEquals(listOf("a2", "b", "c", "d"), model.uiState.value.sessions.map { it.id })
        assertEquals("c3", model.uiState.value.next)
        // Nothing older stays when the first page is the last one.
        first = SessionPage(listOf(row("x", 9_000)), null)
        model.reload()
        assertEquals(listOf("x"), model.uiState.value.sessions.map { it.id })
        assertNull(model.uiState.value.next)
        // A last row without a start date cannot bound the older rows.
        first = SessionPage(listOf(row("y", null)), "n1")
        model.reload()
        assertEquals(listOf("y"), model.uiState.value.sessions.map { it.id })
        assertEquals("n1", model.uiState.value.next)
        // A row without a start date counts as the oldest: it stays after the page, and the cursor stays where it was.
        first = SessionPage(listOf(row("z", 1_000)), "m1")
        model.reload()
        assertEquals(listOf("z", "y"), model.uiState.value.sessions.map { it.id })
        assertEquals("n1", model.uiState.value.next)
    }

    @Test
    fun aRefusedListAndAFailedOneAreShown() = runTest {
        val model = model()
        repo.list = { _, _ -> throw AppException.Unsupported(Features.SESSIONS) }
        model.reload()
        assertTrue(model.uiState.value.unavailable)
        assertTrue(model.uiState.value.loaded)
        assertNull(model.uiState.value.error)
        repo.list = { _, _ -> throw AppException.Unreachable() }
        model.reload()
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
        assertFalse(model.uiState.value.loading)
    }

    @Test
    fun aListThatLandsAfterTheAccountOrTheFilterChangedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<SessionPage>>()
        repo.list = { _, _ -> UncancellableGate<SessionPage>().also { gates.addLast(it) }.await() }
        val model = model()
        // The filter changes while the first page is on its way: the stale page is ignored, the new one applies.
        model.reload()
        val stale = gates.removeFirst()
        model.setFilter(SessionFilter.OPEN)
        val current = gates.removeFirst()
        stale.release(SessionPage(listOf(row("stale", 1)), null))
        assertTrue(model.uiState.value.sessions.isEmpty())
        current.release(SessionPage(listOf(row("open", 2)), null))
        assertEquals(listOf("open"), model.uiState.value.sessions.map { it.id })
        // The account changes while a page, a refusal and a failure are on their way.
        for (outcome in 0..2) {
            accounts.setActive("a1")
            model.reload()
            val inFlight = gates.removeFirst()
            accounts.save(account(id = "b$outcome"))
            when (outcome) {
                0 -> inFlight.release(SessionPage(listOf(row("late", 1)), null))
                1 -> inFlight.fail(AppException.Unsupported(Features.SESSIONS))
                else -> inFlight.fail(AppException.Unreachable())
            }
            assertEquals("b$outcome", model.uiState.value.account?.id)
            assertTrue(model.uiState.value.sessions.isEmpty())
            assertFalse(model.uiState.value.unavailable)
            assertNull(model.uiState.value.error)
        }
    }

    @Test
    fun aListThatLandsAfterTheAccountWasRemovedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<SessionPage>>()
        repo.list = { _, _ -> UncancellableGate<SessionPage>().also { gates.addLast(it) }.await() }
        val model = model()
        for (outcome in 0..2) {
            accounts.save(account())
            accounts.setActive("a1")
            model.reload()
            val inFlight = gates.removeFirst()
            accounts.remove("a1")
            when (outcome) {
                0 -> inFlight.release(SessionPage(listOf(row("late", 1)), null))
                1 -> inFlight.fail(AppException.Unsupported(Features.SESSIONS))
                else -> inFlight.fail(AppException.Unreachable())
            }
            assertNull(model.uiState.value.account)
            assertTrue(model.uiState.value.sessions.isEmpty())
            assertFalse(model.uiState.value.unavailable)
            assertNull(model.uiState.value.error)
        }
        // Nothing to stop and no list to cancel: both are harmless.
        model.stop()
        model.setFilter(SessionFilter.OPEN)
    }

    @Test
    fun noOlderPageIsAskedWithoutAnAccount() = runTest {
        val model = model()
        accounts.remove("a1")
        model.loadMore()
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun theFilterChangesBeforeAnyListWasRead() = runTest {
        val model = model()
        model.setFilter(SessionFilter.OPEN)
        assertEquals(SessionFilter.OPEN, model.uiState.value.filter)
        assertEquals(listOf("a1 true"), repo.calls)
    }

    @Test
    fun theListFollowsTheActiveAccountAndItsSignIn() = runTest {
        val model = model()
        model.start()
        val loaded = repo.calls.size
        // A renamed account is the same account: nothing is read again.
        accounts.update(account(login = "renamed"))
        assertEquals("ada", model.uiState.value.account?.login)
        assertEquals(loaded, repo.calls.size)
        accounts.update(account(signedIn = false))
        assertEquals(false, model.uiState.value.account?.signedIn)
        model.reload()
        model.loadMore()
        model.start()
        assertEquals(loaded, repo.calls.size)
        accounts.save(account(id = "a2"))
        assertEquals("a2", model.uiState.value.account?.id)
        assertEquals(loaded + 1, repo.calls.size)
        accounts.remove("a2")
        accounts.remove("a1")
        assertNull(model.uiState.value.account)
        model.start()
        assertEquals(loaded + 1, repo.calls.size)
    }

    @Test
    fun theFeedReloadsOnAGapAndTellsWhenItReconnects() = runTest {
        features.features = setOf(Features.SESSIONS, Features.EVENTS, Features.TODOS)
        val updates = Channel<LiveUpdate<OrgChange>>(Channel.UNLIMITED)
        events.changes = updates.receiveAsFlow()
        val model = model()
        model.start()
        updates.send(LiveUpdate.Opened(resumed = true))
        assertFalse(model.uiState.value.reconnecting)
        assertEquals(1, repo.calls.size, "The first open of the feed loads, resumed or not")
        updates.send(LiveUpdate.Reconnecting(1_000))
        assertTrue(model.uiState.value.reconnecting)
        updates.send(LiveUpdate.Gap)
        assertEquals(2, repo.calls.size)
        // An event of the feed changes nothing in the list.
        model.onSignal(FeedSignal.Changed(OrgChange(OrgChange.TODO_UPDATED, "s1", null)))
        assertEquals(2, repo.calls.size)
        // The feed fails: what is shown stays, without the reconnecting mark.
        updates.close(AppException.Unreachable())
        assertFalse(model.uiState.value.reconnecting)
        assertNotNull(model.uiState.value.account)
    }

    @Test
    fun stoppingEndsTheFollowAndItsReconnectingMark() = runTest {
        features.features = setOf(Features.SESSIONS, Features.EVENTS, Features.TODOS)
        val updates = Channel<LiveUpdate<OrgChange>>(Channel.UNLIMITED)
        events.changes = updates.receiveAsFlow()
        val model = model()
        model.start()
        updates.send(LiveUpdate.Reconnecting(1_000))
        assertTrue(model.uiState.value.reconnecting)
        model.stop()
        assertFalse(model.uiState.value.reconnecting)
        // Starting again follows again; the view model's end cancels the follow without an error.
        model.start()
        updates.send(LiveUpdate.Opened(resumed = true))
        model.viewModelScope.cancel()
        assertFalse(model.uiState.value.reconnecting)
    }
}
