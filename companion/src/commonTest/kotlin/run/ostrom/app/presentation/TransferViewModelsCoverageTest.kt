// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FakeTransferRepository
import run.ostrom.app.SequenceIds
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SessionTransfer
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferPage
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.TransferRepository
import run.ostrom.app.domain.usecases.transfers.LoadRecipientsUseCase
import run.ostrom.app.domain.usecases.transfers.TransferAction
import run.ostrom.app.domain.usecases.transfers.TransferListUseCase
import run.ostrom.app.domain.usecases.transfers.TransferSessionUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.member
import run.ostrom.app.presentation.navigation.TransferRoute
import run.ostrom.app.presentation.navigation.TransfersRoute
import run.ostrom.app.presentation.viewmodels.TransferError
import run.ostrom.app.presentation.viewmodels.TransferViewModel
import run.ostrom.app.presentation.viewmodels.TransfersViewModel
import run.ostrom.app.transfer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fake transfers, with holds a test sets to look at a call in flight. */
private class HoldingTransfers(val base: FakeTransferRepository) : TransferRepository by base {
    var pageHold: suspend () -> Unit = {}
    var transferHold: suspend () -> Unit = {}
    var decisionHold: suspend () -> Unit = {}
    var decisions = 0

    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?): TransferPage {
        val page = base.transfers(accountId, direction, before)
        pageHold()
        return page
    }

    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer {
        transferHold()
        return base.transfer(accountId, sessionId, request, idempotencyKey)
    }

    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?): SessionTransfer {
        decisions++
        decisionHold()
        return base.accept(accountId, transferId, resume)
    }
}

/** The transfer screens' models at their edges: calls in flight, guards, failures and a member the device does not know. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelsCoverageTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeTransferRepository()
    private val transfers = HoldingTransfers(fake)
    private val features = FakeFeatures(setOf("runners.cloud.v1", "runners.cloud.transfer.v1"))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun accountsOf(account: Account): AccountRepository = accounts().also { it.save(account) }

    private fun form(accounts: AccountRepository) =
        TransferViewModel(
            TransferRoute("a1", "s1", "m1"), LoadRecipientsUseCase(transfers, accounts, fakeGuard()),
            TransferSessionUseCase(transfers, features, accounts, fakeGuard()), accounts, SequenceIds(),
        )

    private fun list(accounts: AccountRepository) = TransfersViewModel(TransfersRoute("a1"), TransferListUseCase(transfers, accounts, fakeGuard()), accounts)

    private fun listCalls() = fake.calls.filter { it.startsWith("list") }

    // ---- The form ---------------------------------------------------------------------------------------------------

    @Test
    fun aFormBeingSentTakesNoReloadAndNoEditAndASentOneTakesNoEdit() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        transfers.transferHold = { gate.await() }
        val model = form(accountsOf(member(memberId = "m1")))
        settle()
        model.onRecipient("m2")
        model.send(); settle()
        assertTrue(model.uiState.value.sending)
        model.reload(); model.onRecipient("m3"); model.onResume(true); settle()
        assertEquals(1, fake.calls.count { it == "recipients" }, "the recipients are not read while sending")
        assertEquals("m2", model.uiState.value.recipientId)
        assertFalse(model.uiState.value.resume)
        gate.complete(Unit); settle()
        assertNotNull(model.uiState.value.done)
        model.onRecipient("m3"); model.onResume(true)
        assertEquals("m2", model.uiState.value.recipientId, "a sent transfer is not edited")
        assertFalse(model.uiState.value.resume)
    }

    @Test
    fun anEditThatChangesNothingKeepsTheKeyOfTheRetry() = runTest(dispatcher) {
        val model = form(accountsOf(member(memberId = "m1")))
        settle()
        model.onRecipient("m2")
        fake.transferResult = { throw AppException.Unreachable() }
        model.send(); settle()
        assertIs<TransferError.Text>(model.uiState.value.error)
        model.onRecipient("m2"); model.onResume(false)
        assertNull(model.uiState.value.error, "an edit clears the failure")
        model.send(); settle()
        val sent = fake.calls.filter { it.startsWith("transfer") }
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1], "the same form goes under the same key")
    }

    @Test
    fun aMemberTheDeviceForgotSendsNothing() = runTest(dispatcher) {
        val accounts = accountsOf(member(memberId = "m1"))
        val model = form(accounts)
        settle()
        model.onRecipient("m2")
        assertTrue(model.uiState.value.canSend)
        accounts.remove("a1")
        model.send(); settle()
        assertTrue(fake.calls.none { it.startsWith("transfer") })
        assertFalse(model.uiState.value.sending)
    }

    @Test
    fun nothingIsSentWithoutARecipient() = runTest(dispatcher) {
        val model = form(accountsOf(member(memberId = "m1")))
        settle()
        assertFalse(model.uiState.value.canSend)
        model.send(); settle()
        assertTrue(fake.calls.none { it.startsWith("transfer") })
    }

    // ---- The list ---------------------------------------------------------------------------------------------------

    @Test
    fun anUnknownMemberHasNoIdentityAndNoActions() = runTest(dispatcher) {
        val none = list(accounts())
        settle()
        assertEquals("", none.uiState.value.memberId)
        assertEquals(emptySet<TransferAction>(), none.actions(transfer("t1")))
        val anonymous = list(accountsOf(account("a1")))
        settle()
        assertEquals("", anonymous.uiState.value.memberId, "an account that does not say who it is")
        val known = list(accountsOf(member(memberId = "m2")))
        settle()
        assertEquals(setOf(TransferAction.ACCEPT, TransferAction.DECLINE), known.actions(transfer("t1", to = "m2")))
    }

    @Test
    fun aDirectionAlreadyReadIsNotReadAgainWhenShownAgain() = runTest(dispatcher) {
        val model = list(accountsOf(member(memberId = "m2")))
        settle()
        model.setDirection(TransferDirection.OUTGOING); settle()
        assertEquals(2, listCalls().size)
        model.setDirection(TransferDirection.INCOMING); settle()
        assertEquals(2, listCalls().size, "its list is already there")
        assertEquals(TransferDirection.INCOMING, model.uiState.value.direction)
    }

    @Test
    fun aFailedListAndAFailedOlderPageAreShown() = runTest(dispatcher) {
        fake.pages = { _, _ -> throw AppException.Unreachable() }
        val model = list(accountsOf(member(memberId = "m2")))
        settle()
        assertIs<TransferError.Text>(model.uiState.value.error)
        assertFalse(model.uiState.value.loading)

        fake.pages = { _, before -> if (before == null) TransferPage(listOf(transfer("t1")), "t1") else throw AppException.Unreachable() }
        model.refresh(); settle()
        assertNull(model.uiState.value.error)
        model.loadMore(); settle()
        assertIs<TransferError.Text>(model.uiState.value.error)
        assertFalse(model.uiState.value.loadingMore)
        assertEquals(listOf("t1"), model.uiState.value.list.transfers.map { it.id })
    }

    @Test
    fun anOlderPageIsAskedOnceAndNotWhileTheListLoadsAndAStaleOneIsDropped() = runTest(dispatcher) {
        fake.pages = { _, before -> if (before == null) TransferPage(listOf(transfer("t1")), "t1") else TransferPage(listOf(transfer("t0")), null) }
        val model = list(accountsOf(member(memberId = "m2")))
        settle()
        // The first page again is on its way: no older page beside it.
        val reload = CompletableDeferred<Unit>()
        transfers.pageHold = { reload.await() }
        model.refresh(); settle()
        assertTrue(model.uiState.value.loading)
        model.loadMore(); settle()
        assertEquals(0, fake.calls.count { it == "list incoming t1" })
        reload.complete(Unit); settle()
        // An older page is on its way: a second one is not asked.
        val older = CompletableDeferred<Unit>()
        var holds = true
        transfers.pageHold = { if (holds) { holds = false; older.await() } }
        model.loadMore(); settle()
        assertTrue(model.uiState.value.loadingMore)
        model.loadMore(); settle()
        assertEquals(1, fake.calls.count { it == "list incoming t1" })
        // The list is read again meanwhile, with another cursor: the older page belongs to the old list.
        fake.pages = { _, _ -> TransferPage(listOf(transfer("t9")), "tX") }
        model.refresh(); settle()
        older.complete(Unit); settle()
        assertFalse(model.uiState.value.loadingMore)
        assertEquals(listOf("t9"), model.uiState.value.list.transfers.map { it.id })
        assertEquals("tX", model.uiState.value.list.nextBefore)
    }

    @Test
    fun aDecisionInFlightIsNotAskedTwiceAndAFailureOtherThanARefusalDoesNotReadTheListAgain() = runTest(dispatcher) {
        val offer = transfer("t1", from = "m1", to = "m2")
        fake.pages = { _, _ -> TransferPage(listOf(offer), null) }
        val model = list(accountsOf(member(memberId = "m2")))
        settle()
        val gate = CompletableDeferred<Unit>()
        transfers.decisionHold = { gate.await() }
        model.accept(offer); settle()
        assertEquals(setOf("t1"), model.uiState.value.acting)
        model.accept(offer); settle()
        assertEquals(1, transfers.decisions, "a decision in flight is not asked twice")
        gate.complete(Unit); settle()
        assertTrue(model.uiState.value.acting.isEmpty())
        assertEquals(TransferState.ACCEPTED, model.uiState.value.list.transfers.single().state)

        val reads = listCalls().size
        transfers.decisionHold = {}
        fake.decision = { _, _ -> throw AppException.Unreachable() }
        model.accept(offer.copy(state = TransferState.OFFERED)); settle()
        assertIs<TransferError.Text>(model.uiState.value.error)
        assertEquals(reads, listCalls().size, "only a refusal reads the list again")
        assertTrue(model.uiState.value.acting.isEmpty())
    }
}
