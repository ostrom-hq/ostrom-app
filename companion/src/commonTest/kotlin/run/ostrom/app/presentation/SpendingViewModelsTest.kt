// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.ActivityItem
import run.ostrom.app.domain.models.ActivityPage
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport
import run.ostrom.app.domain.repositories.ActivityRepository
import run.ostrom.app.domain.repositories.SpendingRepository
import run.ostrom.app.domain.usecases.spending.LoadActivityUseCase
import run.ostrom.app.domain.usecases.spending.LoadSpendingUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.viewmodels.ActivityViewModel
import run.ostrom.app.presentation.viewmodels.SpendingViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpendingViewModelsTest {
    private val accounts = accounts().also { it.save(account()) }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun report(period: SpendPeriod, total: Double = 1.0) = SpendingReport(period, total, null, emptyList(), emptyList(), emptyList())

    private class Spend : SpendingRepository {
        val calls = mutableListOf<String>()
        var answer: suspend (SpendPeriod) -> SpendingReport = { error("unset") }
        override suspend fun report(accountId: String, period: SpendPeriod): SpendingReport { calls += "$accountId ${period.wire}"; return answer(period) }
    }

    private fun spending(repo: Spend) = SpendingViewModel(accounts, LoadSpendingUseCase(repo, FakeFeatures(setOf(Features.BUDGETS)), fakeGuard()))

    @Test
    fun theReportLoadsOnStartAndOnAPeriodChangeOnly() = runTest {
        val repo = Spend().also { it.answer = { p -> report(p) } }
        val model = spending(repo)
        assertTrue(repo.calls.isEmpty())
        model.start()
        assertEquals(SpendPeriod.MONTH, model.uiState.value.report?.period)
        model.setPeriod(SpendPeriod.MONTH)
        assertEquals(listOf("a1 month"), repo.calls)
        model.setPeriod(SpendPeriod.WEEK)
        assertEquals(SpendPeriod.WEEK, model.uiState.value.report?.period)
        assertEquals(listOf("a1 month", "a1 week"), repo.calls)
        assertFalse(model.uiState.value.loading)
    }

    @Test
    fun aFailedLoadKeepsWhatIsShownAndRetryLoadsAgain() = runTest {
        val repo = Spend().also { it.answer = { throw AppException.Unreachable() } }
        val model = spending(repo)
        model.start()
        assertTrue(model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        repo.answer = { p -> report(p) }
        model.retry()
        assertFalse(model.uiState.value.error)
        assertEquals(2, repo.calls.size)
    }

    @Test
    fun aReportThatLandsAfterTheAccountOrThePeriodChangedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<SpendingReport>>()
        val repo = Spend().also { it.answer = { UncancellableGate<SpendingReport>().also { g -> gates.addLast(g) }.await() } }
        val model = spending(repo)
        model.start()
        // The period changes while the month is on its way: the month is dropped, the week shown.
        val month = gates.removeFirst()
        model.setPeriod(SpendPeriod.WEEK)
        val week = gates.removeFirst()
        month.release(report(SpendPeriod.MONTH))
        assertNull(model.uiState.value.report)
        week.release(report(SpendPeriod.WEEK, 2.0))
        assertEquals(2.0, model.uiState.value.report?.totalUsd)
        // The period changes while a failure is on its way.
        model.retry()
        val failing = gates.removeFirst()
        model.setPeriod(SpendPeriod.MONTH)
        val again = gates.removeFirst()
        failing.fail(AppException.Unreachable())
        assertFalse(model.uiState.value.error)
        again.release(report(SpendPeriod.MONTH))
        // The account changes while a report, then a failure, is on its way.
        for (failure in listOf(false, true)) {
            accounts.setActive("a1")
            gates.clear()
            model.retry()
            val inFlight = gates.removeFirst()
            accounts.save(account(id = "b$failure"))
            gates.clear()
            if (failure) inFlight.fail(AppException.Unreachable()) else inFlight.release(report(SpendPeriod.MONTH))
            assertNull(model.uiState.value.report)
            assertFalse(model.uiState.value.error)
        }
    }

    @Test
    fun aReportThatLandsAfterTheAccountWasRemovedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<SpendingReport>>()
        val repo = Spend().also { it.answer = { UncancellableGate<SpendingReport>().also { g -> gates.addLast(g) }.await() } }
        val model = spending(repo)
        model.start()
        for (failure in listOf(false, true)) {
            val inFlight = gates.removeFirst()
            accounts.remove("a1")
            if (failure) inFlight.fail(AppException.Unreachable()) else inFlight.release(report(SpendPeriod.MONTH))
            assertNull(model.uiState.value.account)
            assertNull(model.uiState.value.report)
            assertFalse(model.uiState.value.error)
            accounts.save(account())
            accounts.setActive("a1")
        }
    }

    @Test
    fun theReportFollowsTheActiveAccountAndItsSignIn() = runTest {
        val repo = Spend().also { it.answer = { p -> report(p) } }
        val model = spending(repo)
        accounts.save(account(id = "a2"))
        assertTrue(repo.calls.isEmpty())
        model.start()
        assertEquals(listOf("a2 month"), repo.calls)
        accounts.update(account(id = "a2", login = "renamed"))
        assertEquals(1, repo.calls.size)
        accounts.setActive("a1")
        assertEquals(2, repo.calls.size)
        accounts.update(account(signedIn = false))
        model.retry()
        assertEquals(2, repo.calls.size)
        accounts.remove("a1")
        accounts.remove("a2")
        assertNull(model.uiState.value.account)
        model.retry()
    }

    private fun item(id: String) = ActivityItem(id, 1_000L, "session", "agent", "claude", "did it")

    private class Feed : ActivityRepository {
        val calls = mutableListOf<String>()
        var answer: suspend (String?) -> ActivityPage = { ActivityPage(emptyList(), null) }
        override suspend fun page(accountId: String, before: String?): ActivityPage { calls += "$accountId ${before.orEmpty()}".trim(); return answer(before) }
    }

    private fun activity(feed: Feed) = ActivityViewModel(accounts, LoadActivityUseCase(feed, fakeGuard()))

    @Test
    fun theActivityPagesOnAndNeverRepeatsALine() = runTest {
        val feed = Feed().also { it.answer = { before -> if (before == null) ActivityPage(listOf(item("e1")), "c2") else ActivityPage(listOf(item("e1"), item("e2")), null) } }
        val model = activity(feed)
        model.loadMore()
        model.start()
        model.loadMore()
        assertEquals(listOf("e1", "e2"), model.uiState.value.items.map { it.id })
        assertNull(model.uiState.value.next)
        model.loadMore()
        assertEquals(listOf("a1", "a1 c2"), feed.calls)
    }

    @Test
    fun aFailedOlderPageIsRetriedAndOtherwiseTheFirstPageIs() = runTest {
        var fail = false
        val feed = Feed().also { it.answer = { before ->
            if (fail) throw AppException.Unreachable()
            if (before == null) ActivityPage(listOf(item("e1")), "c2") else ActivityPage(listOf(item("e2")), null)
        } }
        val model = activity(feed)
        model.start()
        fail = true
        model.loadMore()
        assertTrue(model.uiState.value.error)
        fail = false
        model.retry()
        assertEquals(listOf("e1", "e2"), model.uiState.value.items.map { it.id })
        assertFalse(model.uiState.value.error)
        // A failed first page keeps the lines, and Retry reads the first page again.
        fail = true
        model.retry()
        assertTrue(model.uiState.value.error)
        fail = false
        model.retry()
        assertFalse(model.uiState.value.error)
        assertEquals(listOf("a1", "a1 c2", "a1 c2", "a1", "a1"), feed.calls)
    }

    @Test
    fun aPageThatLandsAfterTheAccountOrTheCursorMovedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<ActivityPage>>()
        var gated = false
        val feed = Feed().also { it.answer = { before ->
            if (before == null && !gated) ActivityPage(listOf(item("e1")), "c2")
            else UncancellableGate<ActivityPage>().also { g -> gates.addLast(g) }.await()
        } }
        val model = activity(feed)
        model.start()
        // The first page is read again while an older page is on its way: the older page no longer fits the list.
        model.loadMore()
        val older = gates.removeFirst()
        gated = true
        model.start()
        val refresh = gates.removeFirst()
        refresh.release(ActivityPage(listOf(item("e7")), "c7"))
        older.release(ActivityPage(listOf(item("late")), null))
        assertEquals(listOf("e7"), model.uiState.value.items.map { it.id })
        assertEquals("c7", model.uiState.value.next)
        assertFalse(model.uiState.value.loadingMore)
        // The account changes while a first page, then an older page, is on its way (each a success, then a failure).
        for (older in listOf(false, true)) for (fail in listOf(false, true)) {
            gated = false
            accounts.setActive("a1")
            gated = true
            if (older) model.loadMore() else model.start()
            val inFlight = gates.removeFirst()
            accounts.save(account(id = "b$older$fail"))
            gates.clear()
            if (fail) inFlight.fail(AppException.Unreachable()) else inFlight.release(ActivityPage(listOf(item("x")), null))
            assertFalse(model.uiState.value.error)
            assertTrue(model.uiState.value.items.isEmpty())
        }
    }

    @Test
    fun pagesThatLandAfterTheAccountWasRemovedAreDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<ActivityPage>>()
        val feed = Feed().also { it.answer = { UncancellableGate<ActivityPage>().also { g -> gates.addLast(g) }.await() } }
        val model = activity(feed)
        model.start()
        for (failure in listOf(false, true)) {
            val first = gates.removeFirst()
            accounts.remove("a1")
            if (failure) first.fail(AppException.Unreachable()) else first.release(ActivityPage(listOf(item("late")), null))
            assertNull(model.uiState.value.account)
            assertTrue(model.uiState.value.items.isEmpty())
            assertFalse(model.uiState.value.error)
            accounts.save(account())
            accounts.setActive("a1")
        }
        // An older page: asked once (not doubled, nor while the first page is read again), then the account goes.
        for (failure in listOf(false, true)) {
            gates.removeFirst().release(ActivityPage(listOf(item("e1")), "c2"))
            model.loadMore()
            model.loadMore()
            assertEquals(1, gates.size)
            val older = gates.removeFirst()
            accounts.remove("a1")
            if (failure) older.fail(AppException.Unreachable()) else older.release(ActivityPage(listOf(item("late")), null))
            assertNull(model.uiState.value.account)
            assertFalse(model.uiState.value.error)
            accounts.save(account())
            accounts.setActive("a1")
        }
        // While the first page is read again the older one is not asked.
        gates.removeFirst().release(ActivityPage(listOf(item("e1")), "c2"))
        model.start()
        model.loadMore()
        assertEquals(1, gates.size)
    }

    @Test
    fun theActivityFollowsTheActiveAccountAndItsSignIn() = runTest {
        val feed = Feed().also { it.answer = { ActivityPage(listOf(item("e1")), null) } }
        val model = activity(feed)
        accounts.save(account(id = "a2"))
        assertTrue(feed.calls.isEmpty())
        model.start()
        assertEquals(listOf("a2"), feed.calls)
        accounts.setActive("a1")
        assertEquals(2, feed.calls.size)
        accounts.update(account(signedIn = false))
        model.retry()
        model.loadMore()
        assertEquals(2, feed.calls.size)
        accounts.remove("a1")
        accounts.remove("a2")
        assertNull(model.uiState.value.account)
    }
}
