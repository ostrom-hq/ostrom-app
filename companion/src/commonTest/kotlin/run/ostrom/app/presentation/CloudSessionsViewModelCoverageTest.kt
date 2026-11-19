// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.ConversationCalls
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.SessionKind
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionsUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.viewmodels.CloudSessionsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CoverageAccounts a test changes at will, one change at a time. */
private class CoverageAccounts(list: List<Account>, active: String?) : AccountRepository {
    override val accounts = MutableStateFlow(list)
    override val activeAccountId = MutableStateFlow(active)

    override fun save(account: Account) {
        accounts.value = accounts.value.filter { it.id != account.id } + account
        activeAccountId.value = account.id
    }

    override fun update(account: Account) { accounts.value = accounts.value.map { if (it.id == account.id) account else it } }

    override fun remove(id: String) {
        accounts.value = accounts.value.filter { it.id != id }
        if (activeAccountId.value == id) activeAccountId.value = accounts.value.firstOrNull()?.id
    }

    override fun setActive(id: String) { activeAccountId.value = id }
}

/** A list of sessions answered per account and cursor, that a test can hold; a held read goes on after it was cancelled, as a slow server's would. */
private class CoverageSessionList : ConversationCalls() {
    val asked = mutableListOf<String>()
    var pages: (String, String?) -> CloudSessionPage = { _, _ -> CloudSessionPage(emptyList(), null) }
    var failure: Throwable? = null
    /** The account whose reads fail; every account's when null. */
    var failingAccount: String? = null
    var hold: suspend () -> Unit = {}

    override fun current(sessionId: String) = cloudSession(sessionId)

    override suspend fun sessions(accountId: String, before: String?, kinds: Set<SessionKind>): CloudSessionPage {
        asked += "$accountId ${before ?: "-"}"
        val page = pages(accountId, before)
        try {
            hold()
        } catch (_: CancellationException) {
            // The answer arrives anyway: the screen must not take it for the new account's.
        }
        if (failingAccount == null || accountId == failingAccount) failure?.let { throw it }
        return page
    }

    override suspend fun session(accountId: String, sessionId: String) = cloudSession(sessionId)
    override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String) = cloudSession()
    override suspend fun pause(accountId: String, sessionId: String) = cloudSession(sessionId)
    override suspend fun resume(accountId: String, sessionId: String) = cloudSession(sessionId)
    override suspend fun stop(accountId: String, sessionId: String) = cloudSession(sessionId)
    override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess) = cloudSession(sessionId)
}

/** The list of cloud sessions: the account it follows, its pages, its failures and the races between them. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSessionsViewModelCoverageTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = CoverageSessionList()
    private val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun model(accounts: AccountRepository) =
        CloudSessionsViewModel(accounts, LoadCloudSessionsUseCase(CloudSessionAccess(sessions, features, fakeGuard())))

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun row(id: String, createdAt: Long?): CloudSession = cloudSession(id).copy(createdAt = createdAt)
    private fun ids(model: CloudSessionsViewModel) = model.uiState.value.sessions.map { it.id }

    @Test
    fun theListFollowsTheAccountAndItsSignInAndNothingElse() = runTest(dispatcher) {
        val accounts = CoverageAccounts(emptyList(), null)
        val model = model(accounts)
        settle()
        assertNull(model.uiState.value.account)
        model.start(); model.reload(); model.loadMore(); settle()
        assertTrue(sessions.asked.isEmpty(), "no account, nothing to read")

        sessions.pages = { id, _ -> CloudSessionPage(listOf(row("n-$id", 300)), null) }
        val first = account("a1")
        accounts.save(first); settle()
        assertEquals(listOf("a1 -"), sessions.asked)
        assertEquals(listOf("n-a1"), ids(model))
        // A change of the account's other details keeps the list.
        accounts.update(first.copy(login = "grace")); settle()
        assertEquals(listOf("a1 -"), sessions.asked)
        assertEquals(listOf("n-a1"), ids(model))
        // Signed out: the list is dropped and nothing is read.
        accounts.update(first.copy(signedIn = false)); settle()
        assertEquals(listOf("a1 -"), sessions.asked)
        assertTrue(model.uiState.value.sessions.isEmpty())
        assertFalse(model.uiState.value.loaded)
        model.loadMore(); settle()
        assertEquals(1, sessions.asked.size, "a signed-out account has no older page to read")
        // Another account while the screen shows: its list loads.
        accounts.save(account("a2")); settle()
        assertEquals(listOf("a1 -", "a2 -"), sessions.asked)
        assertEquals(listOf("n-a2"), ids(model))
        // Not showing: a switch reads nothing.
        model.stop()
        accounts.save(account("a3")); settle()
        assertEquals(2, sessions.asked.size)
        // An active account that is not in the list, twice: the second is no change.
        accounts.setActive("ghost"); settle()
        assertNull(model.uiState.value.account)
        accounts.accounts.value = emptyList(); settle()
        assertNull(model.uiState.value.account)
        assertEquals(2, sessions.asked.size)
    }

    @Test
    fun anAnswerForTheAccountThatWasLeftDoesNotReachTheNewOnesList() = runTest(dispatcher) {
        sessions.pages = { id, _ -> CloudSessionPage(listOf(row("n-$id", 300)), "c-$id") }
        val accounts = CoverageAccounts(listOf(account("a1")), "a1")
        val model = model(accounts)
        model.start(); settle()
        assertEquals(listOf("n-a1"), ids(model))
        val gate = CompletableDeferred<Unit>()
        sessions.hold = { gate.await() }
        // The first page again is on its way when the account changes.
        model.reload(); settle()
        assertTrue(model.uiState.value.loading)
        accounts.save(account("a2")); settle()
        gate.complete(Unit); settle()
        assertEquals(listOf("n-a2"), ids(model))
        assertEquals("c-a2", model.uiState.value.next)
        assertFalse(model.uiState.value.loading)
        // An older page on its way too.
        val more = CompletableDeferred<Unit>()
        sessions.hold = { more.await() }
        model.loadMore(); settle()
        assertTrue(model.uiState.value.loadingMore)
        accounts.save(account("a3")); settle()
        more.complete(Unit); settle()
        assertEquals(listOf("n-a3"), ids(model), "the older page of the account that was left is not added")
    }

    @Test
    fun aFailureForTheAccountThatWasLeftIsNotShownOnTheNewOne() = runTest(dispatcher) {
        sessions.pages = { id, _ -> CloudSessionPage(listOf(row("n-$id", 300)), null) }
        val accounts = CoverageAccounts(listOf(account("a1")), "a1")
        val model = model(accounts)
        model.start(); settle()
        var current = "a1"
        for ((index, failure) in listOf(AppException.Unsupported(Features.RUNNERS_CLOUD), AppException.Unreachable()).withIndex()) {
            val gate = CompletableDeferred<Unit>()
            sessions.hold = { gate.await() }
            sessions.failure = failure
            sessions.failingAccount = current
            model.reload(); settle()
            current = "b$index"
            accounts.save(account(current)); settle()
            gate.complete(Unit); settle()
            assertNull(model.uiState.value.error)
            assertFalse(model.uiState.value.unavailable)
            assertFalse(model.uiState.value.loading)
            assertEquals(listOf("n-$current"), ids(model))
        }
        // The account's own read failing shows.
        sessions.hold = {}
        sessions.failingAccount = current
        model.reload(); settle()
        assertNotNull(model.uiState.value.error)
        sessions.failure = AppException.Unsupported(Features.RUNNERS_CLOUD)
        model.reload(); settle()
        assertTrue(model.uiState.value.unavailable)
    }

    @Test
    fun anAnswerThatLandsAfterTheAccountWasRemovedIsDropped() = runTest(dispatcher) {
        sessions.pages = { id, _ -> CloudSessionPage(listOf(row("n-$id", 300)), null) }
        val accounts = CoverageAccounts(listOf(account("a1")), "a1")
        val model = model(accounts)
        model.start(); settle()
        for (failure in listOf<AppException?>(null, AppException.Unsupported(Features.RUNNERS_CLOUD), AppException.Unreachable())) {
            val gate = CompletableDeferred<Unit>()
            sessions.hold = { gate.await() }
            sessions.failure = failure
            sessions.failingAccount = "a1"
            model.reload(); settle()
            accounts.remove("a1"); settle()
            gate.complete(Unit); settle()
            assertNull(model.uiState.value.account)
            assertNull(model.uiState.value.error)
            assertFalse(model.uiState.value.unavailable)
            assertTrue(model.uiState.value.sessions.isEmpty())
            accounts.save(account("a1")); settle()
        }
    }

    @Test
    fun aServerWithoutCloudSessionsMakesTheListUnavailable() = runTest(dispatcher) {
        features.features = emptySet()
        val model = model(CoverageAccounts(listOf(account("a1")), "a1"))
        model.start(); settle()
        assertTrue(model.uiState.value.unavailable)
        assertTrue(model.uiState.value.loaded)
        assertFalse(model.uiState.value.loading)
        assertNull(model.uiState.value.error)
        assertTrue(model.uiState.value.empty)
    }

    @Test
    fun aFailedPageIsRetriedAsTheFirstPageOrAsTheOlderOne() = runTest(dispatcher) {
        sessions.pages = { _, before -> if (before == null) CloudSessionPage(listOf(row("n1", 300)), "c1") else CloudSessionPage(listOf(row("o1", 200)), null) }
        val model = model(CoverageAccounts(listOf(account("a1")), "a1"))
        // The first page fails: the retry reads the first page.
        sessions.failure = AppException.Unreachable()
        model.start(); settle()
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.moreFailed)
        sessions.failure = null
        model.retry(); settle()
        assertNull(model.uiState.value.error)
        assertEquals(listOf("n1"), ids(model))
        assertEquals(listOf("a1 -", "a1 -"), sessions.asked)
        // An older page fails: the retry reads that page.
        sessions.failure = AppException.Unreachable()
        model.loadMore(); settle()
        assertNotNull(model.uiState.value.error)
        assertTrue(model.uiState.value.moreFailed)
        assertFalse(model.uiState.value.loadingMore)
        sessions.failure = null
        model.retry(); settle()
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.moreFailed)
        assertEquals(listOf("n1", "o1"), ids(model))
        assertEquals("a1 c1", sessions.asked.last())
    }

    @Test
    fun olderPagesAddEachSessionOnceAndOnlyWhenThereIsOneToRead() = runTest(dispatcher) {
        val accounts = CoverageAccounts(emptyList(), null)
        val model = model(accounts)
        settle()
        model.loadMore(); settle()
        assertTrue(sessions.asked.isEmpty(), "no account")
        accounts.save(account("a1", signedIn = false)); settle()
        model.loadMore(); settle()
        assertTrue(sessions.asked.isEmpty(), "signed out")

        sessions.pages = { _, before ->
            if (before == null) CloudSessionPage(listOf(row("s1", 300), row("s2", 200)), "c2") else CloudSessionPage(listOf(row("s2", 200), row("s3", 100)), null)
        }
        accounts.save(account("a2")); settle()
        model.start(); settle()
        assertEquals(listOf("s1", "s2"), ids(model))
        // A read of the first page is on its way: no older page is asked beside it.
        val gate = CompletableDeferred<Unit>()
        sessions.hold = { gate.await() }
        model.reload(); settle()
        model.loadMore(); settle()
        assertEquals(0, sessions.asked.count { it == "a2 c2" })
        gate.complete(Unit); settle()
        sessions.hold = {}
        model.loadMore(); settle()
        assertEquals(listOf("s1", "s2", "s3"), ids(model))
        assertNull(model.uiState.value.next)
        assertFalse(model.uiState.value.loadingMore)
        // Nothing older left: nothing is asked.
        val asks = sessions.asked.size
        model.loadMore(); settle()
        assertEquals(asks, sessions.asked.size)
    }

    @Test
    fun theFirstPageAgainKeepsTheOlderRowsItDoesNotCover() = runTest(dispatcher) {
        val first = listOf(row("n1", 500), row("n2", 400))
        val second = listOf(row("gone", 450), row("o1", 300), row("undated", null))
        sessions.pages = { _, before -> if (before == null) CloudSessionPage(first, "c1") else CloudSessionPage(second, null) }
        val model = model(CoverageAccounts(listOf(account("a1")), "a1"))
        model.start(); settle()
        model.loadMore(); settle()
        assertEquals(listOf("n1", "n2", "gone", "o1", "undated"), ids(model))
        assertNull(model.uiState.value.next)
        // A return to the screen: a newer first page replaces what it covers, drops what it no longer lists above its end, keeps the older rows.
        sessions.pages = { _, _ -> CloudSessionPage(listOf(row("n0", 600)) + first, "c9") }
        model.start(); settle()
        assertEquals(listOf("n0", "n1", "n2", "o1", "undated"), ids(model))
        assertNull(model.uiState.value.next, "the older cursor stays: those rows were already read")
        // A first page whose last row has no date, or that is empty, keeps nothing older.
        sessions.pages = { _, _ -> CloudSessionPage(listOf(row("n3", null)), "c8") }
        model.reload(); settle()
        assertEquals(listOf("n3"), ids(model))
        assertEquals("c8", model.uiState.value.next)
        sessions.pages = { _, _ -> CloudSessionPage(emptyList(), "c7") }
        model.reload(); settle()
        assertTrue(model.uiState.value.sessions.isEmpty())
        assertEquals("c7", model.uiState.value.next)
    }
}
