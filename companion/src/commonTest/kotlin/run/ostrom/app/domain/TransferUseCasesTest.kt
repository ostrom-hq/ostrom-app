// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/TransferUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FakeTransferRepository
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferProblem
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.usecases.transfers.LoadRecipientsUseCase
import run.ostrom.app.domain.usecases.transfers.TransferAction
import run.ostrom.app.domain.usecases.transfers.TransferAvailabilityUseCase
import run.ostrom.app.domain.usecases.transfers.TransferListUseCase
import run.ostrom.app.domain.usecases.transfers.TransferRules
import run.ostrom.app.domain.usecases.transfers.TransferSessionUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.member
import run.ostrom.app.transfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferUseCasesTest {
    private val transfers = FakeTransferRepository()
    private val features = FakeFeatures(setOf("runners.cloud.v1", "runners.cloud.transfer.v1"))

    private fun accountsOf(vararg members: run.ostrom.app.domain.models.Account) = accounts().also { repository -> members.forEach { repository.save(it) } }

    @Test fun onlyTheOwnerTransfersACloudSessionThatIsNeitherPreparingNorEnded() {
        val owner = member(memberId = "m1")
        val other = member(memberId = "m9")
        assertTrue(TransferRules.canTransfer(owner, cloudSession("s1", CloudSessionStatus.RUNNING, ownerId = "m1")))
        assertTrue(TransferRules.canTransfer(owner, cloudSession("s1", CloudSessionStatus.PAUSED, ownerId = "m1")))
        assertFalse(TransferRules.canTransfer(other, cloudSession("s1", ownerId = "m1")))
        // An account that does not know its member cannot own anything, even a session whose owner is not told.
        assertFalse(TransferRules.canTransfer(account(), cloudSession("s1", ownerId = null)))
        // A session on a member's device stays theirs: the action is not offered at all.
        assertFalse(TransferRules.canTransfer(owner, cloudSession("s1", ownerId = "m1", remote = true)))
        assertFalse(TransferRules.canTransfer(owner, cloudSession("s1", ownerId = "m1", kind = run.ostrom.app.domain.models.SessionKind.LOCAL)))
        for (status in listOf(CloudSessionStatus.CREATING, CloudSessionStatus.TRANSFERRING, CloudSessionStatus.TERMINATED, CloudSessionStatus.FAILED, CloudSessionStatus.UNKNOWN)) {
            assertFalse(TransferRules.canTransfer(owner, cloudSession("s1", status, ownerId = "m1")), status.name)
        }
    }

    @Test fun theActionShowsOnlyOnAServerThatAnnouncesTransfers() = runTest {
        val accounts = accountsOf(member())
        val availability = TransferAvailabilityUseCase(accounts, features)
        assertTrue(availability.canTransfer("a1", cloudSession("s1", ownerId = "m1")))
        assertTrue(availability.available("a1"))
        features.features = setOf("runners.cloud.v1")
        assertFalse(availability.canTransfer("a1", cloudSession("s1", ownerId = "m1")))
        assertFalse(availability.available("a1"))
        assertFalse(TransferAvailabilityUseCase(accountsOf(account(signedIn = false)), FakeFeatures(setOf("runners.cloud.transfer.v1"))).available("a1"))
    }

    @Test fun theRecipientDecidesAnOfferAndItsOwnerWithdrawsIt() {
        val offer = transfer("t1", from = "m1", to = "m2")
        assertEquals(setOf(TransferAction.ACCEPT, TransferAction.DECLINE), TransferRules.actions(member(memberId = "m2"), offer))
        assertEquals(setOf(TransferAction.CANCEL), TransferRules.actions(member(memberId = "m1"), offer))
        assertEquals(emptySet(), TransferRules.actions(member(memberId = "m3"), offer))
        // Accepted: nobody stops it from the app; a finished one has no action.
        assertEquals(emptySet(), TransferRules.actions(member(memberId = "m1"), offer.copy(state = TransferState.ACCEPTED)))
        assertEquals(emptySet(), TransferRules.actions(member(memberId = "m2"), offer.copy(state = TransferState.COMPLETED)))
    }

    @Test fun theRecipientIsTrimmedAndSentWithItsKeyAndResume() = runTest {
        val accounts = accountsOf(member())
        TransferSessionUseCase(transfers, features, accounts, fakeGuard())("a1", "s1", "m1", TransferRequest(" m2 ", resume = true), "k1")
        assertEquals("transfer s1 m2 resume=true k1", transfers.calls.last())
    }

    @Test fun anInvalidRecipientOrAServerWithoutTransfersIsRefusedBeforeAnyRequest() = runTest {
        val accounts = accountsOf(member())
        val transfer = TransferSessionUseCase(transfers, features, accounts, fakeGuard())
        assertEquals(TransferProblem.RECIPIENT, assertFailsWith<AppException.TransferInvalid> { transfer("a1", "s1", "m1", TransferRequest("m2/../x"), "k") }.problem)
        assertEquals(TransferProblem.RECIPIENT, assertFailsWith<AppException.TransferInvalid> { transfer("a1", "s1", "m1", TransferRequest(" "), "k") }.problem)
        assertEquals(TransferProblem.OWNER, assertFailsWith<AppException.TransferInvalid> { transfer("a1", "s1", "m1", TransferRequest("m1"), "k") }.problem)
        features.features = setOf("runners.cloud.v1")
        assertFailsWith<AppException.Unsupported> { transfer("a1", "s1", "m1", TransferRequest("m2"), "k") }
        assertTrue(transfers.calls.isEmpty())
    }

    @Test fun theRecipientsLeaveTheOwnerOutAndComeSortedByLogin() = runTest {
        transfers.recipients = listOf(TeamMember("m3", "zoe", false), TeamMember("m1", "ada", true), TeamMember("m2", "Carl", false))
        val accounts = accountsOf(member())
        assertEquals(listOf("m2", "m3"), LoadRecipientsUseCase(transfers, accounts, fakeGuard())("a1", "m1").map { it.id })
        assertEquals(listOf("recipients"), transfers.calls)
    }

    @Test fun namesComeFromTheRecipientsAndAnUnknownAccountHasNone() = runTest {
        val list = TransferListUseCase(transfers, accountsOf(member()), fakeGuard())
        assertEquals(mapOf("m2" to "grace", "m1" to "ada"), list.names("a1"))
        assertEquals(emptyMap(), TransferListUseCase(transfers, accountsOf(), fakeGuard()).names("a1"))
    }

    @Test fun nothingIsOfferedForAnAccountThatIsGoneOrSignedOutOrASessionItCannotTransfer() = runTest {
        val none = TransferAvailabilityUseCase(accounts(), features)
        assertFalse(none.available("a1"))
        assertFalse(none.canTransfer("a1", cloudSession("s1", ownerId = "m1")))
        assertFalse(TransferAvailabilityUseCase(accountsOf(member().copy(signedIn = false)), features).canTransfer("a1", cloudSession("s1", ownerId = "m1")))
        assertFalse(TransferAvailabilityUseCase(accountsOf(member()), features).canTransfer("a1", cloudSession("s1", ownerId = "m9")))
    }

    @Test fun recipientsAndTransfersOfAnAccountThatIsGoneAreRefused() = runTest {
        assertFailsWith<AppException.Unauthorized> { LoadRecipientsUseCase(transfers, accounts(), fakeGuard())("a1", "m1") }
        assertFailsWith<AppException.Unauthorized> {
            TransferSessionUseCase(transfers, features, accounts(), fakeGuard())("a1", "s1", "m1", TransferRequest("m2"), "k1")
        }
        assertTrue(transfers.calls.isEmpty())
    }
}
