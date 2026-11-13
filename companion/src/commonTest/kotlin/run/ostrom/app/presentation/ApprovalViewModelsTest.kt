// SPDX-License-Identifier: Apache-2.0
// The cases of the React Native app's useRequestViewModel.test.tsx, in Kotlin.
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeApprovalRepository
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeBiometricGate
import run.ostrom.app.FixedClock
import run.ostrom.app.ORIGIN
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.domain.usecases.approvals.AnswerApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.GetApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.LoadApprovalsUseCase
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.pendingApproval
import run.ostrom.app.presentation.navigation.RequestRoute
import run.ostrom.app.presentation.viewmodels.InboxViewModel
import run.ostrom.app.presentation.viewmodels.RequestViewModel
import run.ostrom.app.presentation.viewmodels.encodePathSegment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalViewModelsTest {
    private val credentials = MemoryCredentialStore()
    private val approvals = FakeApprovalRepository()
    private val gate = FakeBiometricGate()
    private val clock = FixedClock()
    private val accounts = accounts().also { it.save(account()) }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials, clock))
    private val answer = AnswerApprovalUseCase(approvals, PhoneUnlock(credentials, approvals, gate, clock), guard)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun request(id: String = "appr_0001") = RequestViewModel(RequestRoute("a1", id), GetApprovalUseCase(approvals, guard), answer, accounts)

    @Test
    fun theAnswerIsBoundToWhatWasFirstReadUntilThePersonReadsAgain() = runTest {
        val model = request()
        assertEquals("sha256:shown", model.uiState.value.pinned?.shownDigest)
        // A refresh swaps in another command under the same digest: no answer goes.
        approvals.view = pendingApproval(command = "git push --force origin main")
        model.load()
        assertTrue(model.uiState.value.changed)
        assertEquals(DecisionProblem.CHANGED, model.uiState.value.shownProblem)
        model.approve("Approve")
        assertTrue(gate.prompts.isEmpty())
        model.reread()
        assertFalse(model.uiState.value.changed)
        assertNull(model.uiState.value.shownProblem)
        model.approve("Approve")
        assertEquals(1, approvals.answers.size)
        assertEquals(ApprovalStatus.APPROVED, model.uiState.value.approval?.status)
        assertFalse(model.uiState.value.open)
    }

    @Test
    fun aNoGoesOnlyWithItsReasonAndClosesTheField() = runTest {
        val model = request()
        model.startDecline()
        model.setReason("   ")
        model.decline("Decline")
        assertTrue(approvals.answers.isEmpty())
        model.setReason("Wait for the migration")
        model.decline("Decline")
        assertEquals("Wait for the migration", approvals.answers.single().note)
        assertFalse(model.uiState.value.declining)
        assertEquals("", model.uiState.value.reason)
        model.cancelDecline()
    }

    @Test
    fun aFailedAnswerIsWordedAndTheRequestReadAgain() = runTest {
        val model = request()
        approvals.answerError = AppException.StepUpRequired(requestBound = false)
        model.approve("Approve")
        assertEquals(DecisionProblem.STEP_UP, model.uiState.value.problem)
        assertEquals("$ORIGIN/yes/appr_0001", model.uiState.value.consoleUrl)
        model.consoleNotOpened()
        assertTrue(model.uiState.value.consoleFailed)
        model.linkCopied()
        assertTrue(model.uiState.value.copied)
        model.consoleOpened()
        assertFalse(model.uiState.value.consoleFailed)
    }

    @Test
    fun theConsoleLinkEncodesTheIdAndNeedsASignedInAccount() = runTest {
        assertEquals("$ORIGIN/yes/appr%201%2F%C3%A9", request("appr 1/é").uiState.value.consoleUrl)
        assertEquals("a-b_c.d~e", encodePathSegment("a-b_c.d~e"))
        accounts.update(account(signedIn = false))
        assertNull(request().uiState.value.consoleUrl)
    }

    @Test
    fun theInboxKeepsTheLastListWhenARefreshFails() = runTest {
        approvals.queue = ApprovalQueue(listOf(pendingApproval()), listOf(pendingApproval(id = "appr_0000").copy(status = ApprovalStatus.APPROVED)))
        val model = InboxViewModel(accounts, LoadApprovalsUseCase(approvals, guard))
        assertEquals(listOf("appr_0001"), model.uiState.value.pending.map { it.id })
        assertEquals(listOf("appr_0000"), model.uiState.value.recent.map { it.id })
        assertFalse(model.uiState.value.stale)
        approvals.queueError = AppException.OrganizationRequired()
        model.refresh()
        assertTrue(model.uiState.value.stale)
        assertTrue(model.uiState.value.orgRequired)
        assertEquals(1, model.uiState.value.pending.size)
    }
}
