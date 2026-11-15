// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../presentation/TransferViewModelsTest.kt; see NOTICE.
package run.ostrom.app.presentation

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
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferPage
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.usecases.transfers.LoadRecipientsUseCase
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val transfers = FakeTransferRepository()
    private val features = FakeFeatures(setOf("runners.cloud.v1", "runners.cloud.transfer.v1"))

    private fun accountsOf(account: Account) = accounts().also { it.save(account) }

    private fun form(accounts: run.ostrom.app.domain.repositories.AccountRepository, ownerId: String = "m1"): TransferViewModel =
        TransferViewModel(TransferRoute("a1", "s1", ownerId), LoadRecipientsUseCase(transfers, accounts, fakeGuard()), TransferSessionUseCase(transfers, features, accounts, fakeGuard()), accounts, SequenceIds())

    private fun list(accounts: run.ostrom.app.domain.repositories.AccountRepository) =
        TransfersViewModel(TransfersRoute("a1"), TransferListUseCase(transfers, accounts, fakeGuard()), accounts)

    @Test fun aMemberSearchesAMemberAndSendsAnOfferWithResume() = runTest(dispatcher) {
        transfers.recipients = listOf(TeamMember("m2", "grace", false), TeamMember("m3", "linus", false), TeamMember("m1", "ada", true))
        val model = form(accountsOf(member(memberId = "m1")))
        settle()
        assertEquals(listOf("m2", "m3"), model.uiState.value.members.map { it.id }, "The owner is left out")
        model.onQuery("GRA")
        assertEquals(listOf("m2"), model.uiState.value.matches.map { it.id })
        assertFalse(model.uiState.value.canSend, "Nobody is picked yet")
        model.onRecipient("m2"); model.onResume(true)
        assertTrue(model.uiState.value.canSend)
        model.send(); settle()
        assertTrue(transfers.calls.last { it.startsWith("transfer") }.startsWith("transfer s1 m2 resume=true id-"), transfers.calls.last())
        assertEquals(TransferState.OFFERED, model.uiState.value.done?.state)
        assertFalse(model.uiState.value.canSend)
    }

    @Test fun aRetryKeepsTheKeyAndAnotherRecipientOrOptionDrawsANewOne() = runTest(dispatcher) {
        val model = form(accountsOf(member(memberId = "m1")))
        settle()
        model.onRecipient("m2")
        transfers.transferResult = { throw AppException.Unreachable() }
        model.send(); settle()
        assertIs<TransferError.Text>(model.uiState.value.error)
        // The same form again: the same key, so the first transfer is replayed if it did arrive.
        model.send(); settle()
        val sent = transfers.calls.filter { it.startsWith("transfer") }
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        model.onResume(true)
        transfers.transferResult = { throw AppException.TransferRefused("recipient_ineligible") }
        model.send(); settle()
        val third = transfers.calls.last { it.startsWith("transfer") }
        assertNotEquals(sent[0].substringAfterLast(' '), third.substringAfterLast(' '))
        // The refusal keeps its key, for the screen to word.
        assertEquals(TransferError.Refused("recipient_ineligible"), model.uiState.value.error)
        assertNull(model.uiState.value.done)
    }

    @Test fun aFailedRecipientsListCanBeReadAgain() = runTest(dispatcher) {
        val failing = object : run.ostrom.app.domain.repositories.TransferRepository by transfers {
            var fail = true
            override suspend fun recipients(accountId: String): List<TeamMember> {
                if (fail) { fail = false; throw AppException.TransferRefused("transfer_disabled") }
                return transfers.recipients(accountId)
            }
        }
        val accounts = accountsOf(member(memberId = "m1"))
        val model = TransferViewModel(TransferRoute("a1", "s1", "m1"), LoadRecipientsUseCase(failing, accounts, fakeGuard()), TransferSessionUseCase(transfers, features, accounts, fakeGuard()), accounts, SequenceIds())
        settle()
        assertEquals(TransferError.Refused("transfer_disabled"), model.uiState.value.error)
        assertTrue(model.uiState.value.members.isEmpty())
        model.reload(); settle()
        assertNull(model.uiState.value.error)
        assertEquals(listOf("m2"), model.uiState.value.members.map { it.id })
    }

    @Test fun anOfferIsAcceptedFromTheIncomingListAndARefusalShowsItsCurrentState() = runTest(dispatcher) {
        val offer = transfer("t1", from = "m1", to = "m2")
        transfers.pages = { direction, _ -> if (direction == TransferDirection.INCOMING) TransferPage(listOf(offer), null) else TransferPage(listOf(transfer("t2", from = "m2", to = "m3")), null) }
        val model = list(accountsOf(member(memberId = "m2")))
        settle()
        assertEquals(mapOf("m2" to "grace", "m1" to "ada"), model.uiState.value.names)
        assertEquals(listOf("t1"), model.uiState.value.list.transfers.map { it.id })
        model.accept(offer); settle()
        assertEquals(TransferState.ACCEPTED, model.uiState.value.list.transfers.single().state)
        assertEquals("accept t1 null", transfers.calls.last { it.startsWith("accept") })
        // Withdrawing someone else's offer is not offered: nothing is sent.
        model.cancel(offer.copy(state = TransferState.OFFERED)); settle()
        assertFalse(transfers.calls.any { it.startsWith("cancel") })

        // Their own offer, in the other direction, is withdrawn by them.
        model.setDirection(TransferDirection.OUTGOING); settle()
        val mine = model.uiState.value.list.transfers.single()
        model.cancel(mine); settle()
        assertEquals("cancel t2", transfers.calls.last { it.startsWith("cancel") })
        assertEquals(TransferState.CANCELLED, model.uiState.value.list.transfers.single().state)
    }

    @Test fun aRefusedDecisionReadsTheListAgain() = runTest(dispatcher) {
        val offer = transfer("t1", from = "m1", to = "m2")
        transfers.pages = { _, _ -> TransferPage(listOf(offer), null) }
        val model = list(accountsOf(member(memberId = "m2")))
        settle()
        transfers.decision = { _, _ -> throw AppException.TransferRefused("transfer_expired") }
        transfers.pages = { _, _ -> TransferPage(listOf(offer.copy(state = TransferState.EXPIRED)), null) }
        model.accept(offer); settle()
        assertEquals(TransferError.Refused("transfer_expired"), model.uiState.value.error)
        // The list is read again, under the refusal: the offer expired meanwhile.
        assertEquals(TransferState.EXPIRED, model.uiState.value.list.transfers.single().state)
        assertTrue(model.uiState.value.acting.isEmpty())
    }

    @Test fun theListPagesFurtherWithoutShowingATransferTwice() = runTest(dispatcher) {
        transfers.pages = { _, before -> if (before == null) TransferPage(listOf(transfer("t1")), "t1") else TransferPage(listOf(transfer("t1"), transfer("t0")), null) }
        val model = list(accountsOf(member(memberId = "m9")))
        settle()
        model.loadMore(); settle()
        assertEquals(listOf("t1", "t0"), model.uiState.value.list.transfers.map { it.id })
        assertNull(model.uiState.value.list.nextBefore)
    }

    @Test fun theNamesAreOnlyAComfort() = runTest(dispatcher) {
        val refusing = object : run.ostrom.app.domain.repositories.TransferRepository by transfers {
            override suspend fun recipients(accountId: String): List<TeamMember> = throw AppException.TransferRefused("transfer_disabled")
        }
        transfers.pages = { _, _ -> TransferPage(listOf(transfer("t1", to = "m2")), null) }
        val accounts = accountsOf(member(memberId = "m2"))
        val model = TransfersViewModel(TransfersRoute("a1"), TransferListUseCase(refusing, accounts, fakeGuard()), accounts)
        settle()
        assertTrue(model.uiState.value.names.isEmpty())
        assertNull(model.uiState.value.error)
        assertEquals(listOf("t1"), model.uiState.value.list.transfers.map { it.id })
    }
}

/** Runs what is due within a second of virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
