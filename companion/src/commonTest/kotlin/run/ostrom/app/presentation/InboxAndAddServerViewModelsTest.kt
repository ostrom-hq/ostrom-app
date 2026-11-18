// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.demo.DemoAccountRepository
import run.ostrom.app.demo.DemoContent
import run.ostrom.app.demo.DemoMode
import run.ostrom.app.demo.StartDemoUseCase
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.usecases.approvals.LoadApprovalsUseCase
import run.ostrom.app.domain.usecases.auth.InspectServerUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.presentation.viewmodels.AddServerViewModel
import run.ostrom.app.presentation.viewmodels.InboxViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InboxAndAddServerViewModelsTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Approvals : ApprovalRepository {
        val reads = ArrayDeque<UncancellableGate<ApprovalQueue>>()
        override suspend fun queue(accountId: String): ApprovalQueue = UncancellableGate<ApprovalQueue>().also { reads.addLast(it) }.await()
        override suspend fun view(accountId: String, id: String): Approval = error("not used")
        override suspend fun answer(accountId: String, id: String, answer: ApprovalAnswer, shownDigest: String, note: String?): Approval = error("not used")
        override suspend fun unlock(accountId: String, unlockKey: String, requestId: String?) = error("not used")
    }

    private val empty = ApprovalQueue(emptyList(), emptyList())

    @Test
    fun theInboxHasNothingToRefreshWithoutAnAccount() = runTest {
        val approvals = Approvals()
        val model = InboxViewModel(accounts(), LoadApprovalsUseCase(approvals, fakeGuard()))
        model.refresh()
        assertTrue(approvals.reads.isEmpty())
        assertNull(model.uiState.value.accountId)
    }

    @Test
    fun aListThatLandsAfterTheAccountChangedIsDropped() = runTest {
        val accounts = accounts().also { it.save(account()) }
        val approvals = Approvals()
        val model = InboxViewModel(accounts, LoadApprovalsUseCase(approvals, fakeGuard()))
        approvals.reads.removeFirst().release(empty)
        assertTrue(model.uiState.value.loaded)
        for (outcome in 0..2) {
            model.refresh()
            val inFlight = approvals.reads.removeFirst()
            // A refresh already running is not doubled.
            model.refresh()
            assertTrue(approvals.reads.isEmpty())
            accounts.save(account(id = "b$outcome"))
            val other = approvals.reads.removeFirst()
            when (outcome) {
                0 -> inFlight.release(ApprovalQueue(listOf(run.ostrom.app.pendingApproval()), emptyList()))
                1 -> inFlight.fail(CancellationException("closing"))
                else -> inFlight.fail(AppException.OrganizationRequired())
            }
            other.release(empty)
            assertEquals("b$outcome", model.uiState.value.accountId)
            assertTrue(model.uiState.value.pending.isEmpty())
            assertNull(model.uiState.value.error)
            assertFalse(model.uiState.value.orgRequired)
            assertFalse(model.uiState.value.refreshing)
        }
    }

    @Test
    fun aReadCancelledOnTheSameAccountStopsItsSpinnerOnly() = runTest {
        val accounts = accounts().also { it.save(account()) }
        val approvals = Approvals()
        val model = InboxViewModel(accounts, LoadApprovalsUseCase(approvals, fakeGuard()))
        assertTrue(model.uiState.value.refreshing)
        approvals.reads.removeFirst().fail(CancellationException("closing"))
        assertFalse(model.uiState.value.refreshing)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.loaded)
    }

    @Test
    fun aFailedRefreshKeepsTheListAndSaysWhichOrganizationIsNeeded() = runTest {
        val accounts = accounts().also { it.save(account()) }
        val approvals = Approvals()
        val model = InboxViewModel(accounts, LoadApprovalsUseCase(approvals, fakeGuard()))
        approvals.reads.removeFirst().release(ApprovalQueue(listOf(run.ostrom.app.pendingApproval()), emptyList()))
        model.refresh()
        approvals.reads.removeFirst().fail(AppException.OrganizationRequired())
        assertTrue(model.uiState.value.orgRequired)
        assertTrue(model.uiState.value.stale)
        assertEquals(1, model.uiState.value.pending.size)
        model.refresh()
        approvals.reads.removeFirst().release(empty)
        assertFalse(model.uiState.value.orgRequired)
        assertNull(model.uiState.value.error)
    }

    private class Auth : FakeAuthRepository() {
        var gate: UncancellableGate<ServerInfo>? = null
        override suspend fun discover(origin: String): ServerInfo = gate?.await()?.copy(origin = origin) ?: super.discover(origin)
    }

    private fun addServer(auth: Auth, accounts: run.ostrom.app.domain.repositories.AccountRepository) =
        AddServerViewModel(InspectServerUseCase(auth), accounts, StartDemoUseCase(accounts))

    @Test
    fun anAddressIsCheckedOnceAndAnEmptyOneNever() = runTest {
        val auth = Auth()
        val model = addServer(auth, accounts())
        model.check()
        assertFalse(model.uiState.value.checking)
        model.onOriginChange("ostrom.example.org")
        auth.gate = UncancellableGate()
        model.check()
        assertTrue(model.uiState.value.checking)
        // Checking again while it runs asks nothing more.
        model.check()
        // Typing again starts over: the answer of the earlier address is not shown.
        model.onOriginChange("other.example.org")
        assertFalse(model.uiState.value.checking)
        auth.gate = null
        model.check()
        assertEquals("https://other.example.org", model.uiState.value.server?.origin)
        assertTrue(model.uiState.value.newServer)
    }

    @Test
    fun anUnreachableServerSaysSo() = runTest {
        val auth = Auth().also { it.discoverError = AppException.Unreachable() }
        val model = addServer(auth, accounts())
        model.onOriginChange("ostrom.example.org")
        model.check()
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
        assertFalse(model.uiState.value.checking)
    }

    @Test
    fun theDemoIsStartedOnlyWhereItIsOffered() = runTest {
        // A repository that is not the demo-aware one never offers the demo.
        val plain = addServer(Auth(), accounts())
        assertFalse(plain.uiState.value.demoOffered)
        plain.tryDemo()
        val auth = Auth()
        val real = SettingsAccountRepository(MapSettings())
        val demoAccounts = DemoAccountRepository(real, DemoMode(FixedClock()))
        val model = addServer(auth, demoAccounts)
        assertTrue(model.uiState.value.demoOffered)
        // A check in flight is dropped when the demo starts.
        model.onOriginChange("ostrom.example.org")
        auth.gate = UncancellableGate()
        model.check()
        model.tryDemo()
        assertEquals(DemoContent.ACCOUNT_ID, demoAccounts.activeAccountId.value)
        assertNotNull(demoAccounts.accounts.value.singleOrNull())
    }
}
