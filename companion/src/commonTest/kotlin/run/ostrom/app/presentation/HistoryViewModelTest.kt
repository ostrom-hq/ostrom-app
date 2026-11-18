// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.ReceiptCheck
import run.ostrom.app.domain.models.ReceiptPage
import run.ostrom.app.domain.models.ReceiptSummary
import run.ostrom.app.domain.models.ServedReceiptKey
import run.ostrom.app.domain.repositories.ReceiptKeyPinRepository
import run.ostrom.app.domain.repositories.ReceiptRepository
import run.ostrom.app.domain.usecases.receipts.CheckReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.LoadReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.TrustedReceiptKeysUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.viewmodels.HistoryUiState
import run.ostrom.app.presentation.viewmodels.HistoryViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val accounts = accounts().also { it.save(account()) }
    private val repo = Repo()

    private class Repo : ReceiptRepository {
        val calls = mutableListOf<String>()
        var page: suspend (String?) -> ReceiptPage = { ReceiptPage(emptyList(), null) }
        override suspend fun page(accountId: String, before: String?): ReceiptPage { calls += "$accountId ${before.orEmpty()}".trim(); return page(before) }
        override suspend fun get(accountId: String, id: String): HistoryEntry = error("not used")
        override suspend fun keys(accountId: String): List<ServedReceiptKey> = emptyList()
    }

    private class Pins : ReceiptKeyPinRepository {
        override suspend fun read(origin: String): Map<String, String> = emptyMap()
        override suspend fun write(origin: String, pins: Map<String, String>) = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(): HistoryViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val guard = fakeGuard()
        return HistoryViewModel(
            accounts,
            LoadReceiptsUseCase(repo, FakeFeatures(setOf(Features.RECEIPTS)), guard),
            TrustedReceiptKeysUseCase(repo, Pins(), accounts, guard, dispatcher),
            CheckReceiptsUseCase(dispatcher),
        )
    }

    private fun raw(id: String?, extra: String? = null): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        extra?.let { put("extra", it) }
    }

    /** A receipt this app can read (with its summary), or one of a newer shape (without). */
    private fun entry(id: String?, extra: String? = null, readable: Boolean = true) = HistoryEntry(
        raw(id, extra),
        if (readable && id != null) ReceiptSummary(id, "approved", false, "ada", "gate", "g1", 1_000L) else null,
    )

    @Test
    fun resultsAndPagesLandOnlyOnTheAccountThatAskedAndOnTheSameReceipt() {
        val one = entry("r1")
        val two = entry("r2")
        val shown = HistoryUiState(account = account("a1"), entries = listOf(one, two))
        val verified = one.copy(check = ReceiptCheck.Verified("k1"))
        // The same content read again (another object) shows the same result; another receipt keeps its own.
        val again = shown.copy(entries = listOf(entry("r1"), two)).withChecked("a1", listOf(verified))
        assertEquals(listOf(ReceiptCheck.Verified("k1"), two.check), again.entries.map { it.check })
        // Another account's list, or none, is left as it is.
        assertEquals(shown, shown.withChecked("a2", listOf(verified)))
        assertEquals(HistoryUiState(), HistoryUiState().withChecked("a1", listOf(verified)))
        assertEquals(shown, shown.withFirstPage("a2", listOf(entry("r9")), "c9"))
        assertEquals(HistoryUiState(), HistoryUiState().withFirstPage("a1", listOf(entry("r9")), "c9"))
        val first = shown.copy(refreshing = true).withFirstPage("a1", listOf(entry("r9")), "c9")
        assertEquals(listOf("r9"), first.entries.map { it.summary?.id })
        assertEquals("c9", first.next)
        assertTrue(first.loaded)
        assertFalse(first.refreshing)
    }

    @Test
    fun receiptsAreCheckedAndOlderPagesNeverRepeatOneAlreadyShown() = runTest {
        repo.page = { before ->
            if (before == null) ReceiptPage(listOf(entry("r1"), entry(null, readable = false)), "c2")
            else ReceiptPage(listOf(entry("r1"), entry("r2"), entry("r3", readable = false)), null)
        }
        val model = model()
        model.loadMore()
        model.start()
        assertTrue(model.uiState.value.loaded)
        // Nothing trusted: every row is read, none shown as verified.
        assertTrue(model.uiState.value.entries.all { it.check == ReceiptCheck.NoKey })
        model.loadMore()
        assertEquals(listOf("a1", "a1 c2"), repo.calls)
        // r1 is not repeated; the row without a summary is kept, and so is the unreadable one of the next page.
        assertEquals(listOf("r1", null, "r2", "r3"), model.uiState.value.entries.map { it.raw["id"]?.toString()?.trim('"') })
        assertNull(model.uiState.value.next)
        // Nothing more to read, and a read already running is not doubled.
        model.loadMore()
        assertEquals(2, repo.calls.size)
        assertFalse(model.uiState.value.stale)
    }

    @Test
    fun aReadAgainShowsTheResultAlreadyFoundUnlessTheReceiptChanged() = runTest {
        val gate = UncancellableGate<ReceiptPage>()
        var content = "one"
        var cursor = "c2"
        repo.page = { before -> if (before == null) ReceiptPage(listOf(entry("r1", content)), cursor) else gate.await() }
        val model = model()
        model.start()
        model.refresh()
        assertEquals(ReceiptCheck.NoKey, model.uiState.value.entries.single().check)
        content = "two"
        model.refresh()
        assertEquals(ReceiptCheck.NoKey, model.uiState.value.entries.single().check)
        assertEquals("two", model.uiState.value.entries.single().raw["extra"]?.toString()?.trim('"'))
        // An older page is on its way when the list is read again from its first page: the older page is dropped.
        model.loadMore()
        assertTrue(model.uiState.value.loadingMore)
        // A read already running is not doubled.
        model.loadMore()
        cursor = "c3"
        model.refresh()
        assertFalse(model.uiState.value.loadingMore)
        gate.release(ReceiptPage(listOf(entry("late")), null))
        assertEquals(listOf("r1"), model.uiState.value.entries.map { it.summary?.id })
        assertEquals("c3", model.uiState.value.next)
        assertFalse(model.uiState.value.loadingMore)
    }

    @Test
    fun aFailedOlderPageIsRetriedAndAFailedRefreshKeepsTheRows() = runTest {
        var fail = false
        repo.page = { before ->
            if (fail) throw AppException.Unreachable()
            if (before == null) ReceiptPage(listOf(entry("r1")), "c2") else ReceiptPage(listOf(entry("r2")), null)
        }
        val model = model()
        model.start()
        fail = true
        model.loadMore()
        assertTrue(model.uiState.value.moreFailed)
        fail = false
        model.retry()
        assertFalse(model.uiState.value.moreFailed)
        assertEquals(listOf("r1", "r2"), model.uiState.value.entries.map { it.summary?.id })
        // Nothing failed any more: Retry reads the first page again, and a failure there is a stale list.
        fail = true
        model.retry()
        assertTrue(model.uiState.value.failed)
        assertTrue(model.uiState.value.stale)
        assertEquals(2, model.uiState.value.entries.size)
    }

    @Test
    fun aPageThatLandsAfterTheAccountChangedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<ReceiptPage>>()
        repo.page = { UncancellableGate<ReceiptPage>().also { gates.addLast(it) }.await() }
        val model = model()
        model.start()
        // The account changes while the first page is on its way (a success, then a failure).
        for (failure in listOf(false, true)) {
            val inFlight = gates.removeFirst()
            accounts.save(account(id = "b$failure"))
            if (failure) inFlight.fail(AppException.Unreachable()) else inFlight.release(ReceiptPage(listOf(entry("late")), null))
            assertEquals("b$failure", model.uiState.value.account?.id)
            assertTrue(model.uiState.value.entries.isEmpty())
            assertFalse(model.uiState.value.failed)
        }
    }

    @Test
    fun anOlderPageThatLandsAfterTheAccountChangedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<ReceiptPage>>()
        repo.page = { before ->
            if (before == null) ReceiptPage(listOf(entry("r1")), "c2") else UncancellableGate<ReceiptPage>().also { gates.addLast(it) }.await()
        }
        val model = model()
        model.start()
        for (failure in listOf(false, true)) {
            accounts.setActive("a1")
            model.loadMore()
            val older = gates.removeFirst()
            accounts.save(account(id = "b$failure"))
            if (failure) older.fail(AppException.Unreachable()) else older.release(ReceiptPage(listOf(entry("late")), null))
            // The other account keeps its own list, untouched by the page of the first.
            assertEquals(listOf("r1"), model.uiState.value.entries.map { it.summary?.id })
            assertFalse(model.uiState.value.moreFailed)
            assertEquals("c2", model.uiState.value.next)
        }
    }

    @Test
    fun noOlderPageIsAskedWithoutAnAccountOrWhileTheFirstOneIsRead() = runTest {
        val gates = ArrayDeque<UncancellableGate<ReceiptPage>>()
        repo.page = { UncancellableGate<ReceiptPage>().also { gates.addLast(it) }.await() }
        val model = model()
        model.start()
        gates.removeFirst().release(ReceiptPage(listOf(entry("r1")), "c2"))
        model.refresh()
        assertTrue(model.uiState.value.refreshing)
        model.loadMore()
        assertEquals(1, gates.size, "only the refresh is asked")
        gates.removeFirst().release(ReceiptPage(listOf(entry("r1")), "c2"))
        accounts.remove("a1")
        model.loadMore()
        assertTrue(gates.isEmpty())
    }

    @Test
    fun pagesThatLandAfterTheAccountWasRemovedAreDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<ReceiptPage>>()
        repo.page = { UncancellableGate<ReceiptPage>().also { gates.addLast(it) }.await() }
        val model = model()
        model.start()
        for (failure in listOf(false, true)) {
            val first = gates.removeFirst()
            accounts.remove("a1")
            if (failure) first.fail(AppException.Unreachable()) else first.release(ReceiptPage(listOf(entry("late")), null))
            assertNull(model.uiState.value.account)
            assertTrue(model.uiState.value.entries.isEmpty())
            assertFalse(model.uiState.value.failed)
            accounts.save(account())
            accounts.setActive("a1")
        }
        // An older page: the account goes while it is on its way.
        for (failure in listOf(false, true)) {
            gates.removeFirst().release(ReceiptPage(listOf(entry("r1")), "c2"))
            model.loadMore()
            val older = gates.removeFirst()
            accounts.remove("a1")
            if (failure) older.fail(AppException.Unreachable()) else older.release(ReceiptPage(listOf(entry("late")), null))
            assertNull(model.uiState.value.account)
            assertFalse(model.uiState.value.moreFailed)
            accounts.save(account())
            accounts.setActive("a1")
        }
    }

    @Test
    fun theListFollowsTheActiveAccountAndItsSignIn() = runTest {
        repo.page = { ReceiptPage(listOf(entry("r1")), null) }
        val model = model()
        // Before the tab shows, an account change loads nothing.
        accounts.save(account(id = "a2"))
        assertTrue(repo.calls.isEmpty())
        model.start()
        assertEquals(listOf("a2"), repo.calls)
        accounts.update(account(id = "a2", login = "renamed"))
        assertEquals(1, repo.calls.size)
        accounts.setActive("a1")
        assertEquals(listOf("a2", "a1"), repo.calls)
        accounts.update(account(signedIn = false))
        model.refresh()
        model.retry()
        model.loadMore()
        assertEquals(2, repo.calls.size)
        // The account that is left is read.
        accounts.remove("a1")
        assertEquals(3, repo.calls.size)
        accounts.remove("a2")
        assertNull(model.uiState.value.account)
        model.refresh()
        assertEquals(3, repo.calls.size)
    }
}
