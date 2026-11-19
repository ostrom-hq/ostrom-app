// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeApprovalRepository
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.ApprovalViewer
import run.ostrom.app.domain.models.NoApprove
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.domain.usecases.approvals.AnswerApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.GetApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.pendingApproval
import run.ostrom.app.presentation.navigation.RequestRoute
import run.ostrom.app.presentation.viewmodels.RequestUiState
import run.ostrom.app.presentation.viewmodels.RequestViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The request screen's derived flags, and its guards while a read or an answer is in flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestViewModelCoverageTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** The fake repository, whose reads can wait on [viewGate] and fail with [viewError]. */
    private class Approvals(val inner: FakeApprovalRepository) : ApprovalRepository by inner {
        var viewGate: CompletableDeferred<Unit>? = null
        var viewError: Exception? = null
        var views = 0

        override suspend fun view(accountId: String, id: String): Approval {
            views++
            viewGate?.await()
            viewError?.let { throw it }
            return inner.view(accountId, id)
        }
    }

    private val credentials = MemoryCredentialStore()
    private val fake = FakeApprovalRepository()
    private val approvals = Approvals(fake)
    /** A device that confirms every prompt, after [wait] when set. */
    private class Gate : BiometricGate {
        override val supported = true
        val prompts = mutableListOf<String>()
        var wait: CompletableDeferred<Unit>? = null

        override suspend fun confirm(prompt: String): BiometricOutcome {
            prompts += prompt
            wait?.await()
            return BiometricOutcome.CONFIRMED
        }
    }

    private val gate = Gate()
    private val clock = FixedClock()
    private val accounts = accounts().also { it.save(account()) }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials, clock))
    private val answer = AnswerApprovalUseCase(approvals, PhoneUnlock(credentials, approvals, gate, clock), guard)

    private fun request(repository: run.ostrom.app.domain.repositories.AccountRepository = accounts) =
        RequestViewModel(RequestRoute("a1", "appr_0001"), GetApprovalUseCase(approvals, guard), answer, repository)

    @Test fun theFlagsOfAStateWithoutARequestAreAllOff() {
        val state = RequestUiState()
        assertFalse(state.open); assertFalse(state.askedByMe); assertFalse(state.mayApprove); assertFalse(state.changed)
        assertNull(state.noApprove)
    }

    @Test fun theFlagsFollowTheRequestAndItsViewer() {
        val pending = pendingApproval()
        val state = RequestUiState(approval = pending, pinned = pending.reading)
        assertTrue(state.open); assertFalse(state.askedByMe); assertTrue(state.mayApprove); assertFalse(state.changed)
        assertNull(state.noApprove)
        assertTrue(state.copy(approval = pendingApproval(viewer = ApprovalViewer(self = true))).askedByMe)
        assertFalse(state.copy(approval = pending.copy(status = ApprovalStatus.APPROVED)).open)
        val refused = state.copy(approval = pendingApproval(viewer = ApprovalViewer(canApprove = false)))
        assertEquals(NoApprove.REFUSED, refused.noApprove); assertFalse(refused.mayApprove)
        assertEquals(NoApprove.TRUNCATED, state.copy(approval = pendingApproval(diffTruncated = true)).noApprove)
    }

    @Test fun aRequestIsChangedOnlyWhenWhatWasPinnedDiffers() {
        val pending = pendingApproval()
        assertFalse(RequestUiState(approval = pending, pinned = null).changed, "nothing pinned yet")
        assertFalse(RequestUiState(approval = pending, pinned = pending.reading).changed)
        assertTrue(RequestUiState(approval = pending, pinned = pendingApproval(digest = "sha256:other").reading).changed)
        // An answer goes only bound to a pinned reading that is still the one shown, and never twice at once.
        assertTrue(RequestUiState(approval = pending, pinned = pending.reading).answerable)
        assertFalse(RequestUiState(approval = pending, pinned = null).answerable, "nothing pinned to bind the answer to")
        assertFalse(RequestUiState(approval = pending, pinned = pendingApproval(digest = "sha256:other").reading).answerable)
        assertFalse(RequestUiState(approval = pending, pinned = pending.reading, sending = ApprovalAnswer.APPROVE).answerable)
    }

    @Test fun aReadInFlightIsNotStartedAgainAndNothingGoesBeforeTheRequestIsRead() = runTest {
        approvals.viewGate = CompletableDeferred()
        val model = request()
        assertTrue(model.uiState.value.loading)
        model.load()
        assertEquals(1, approvals.views, "the read in flight is the only one")
        // Without a request, no answer and no pinned reading: the first read pins it.
        model.reread()
        assertNull(model.uiState.value.pinned)
        model.approve("Approve")
        model.setReason("Wait")
        model.decline("Decline")
        assertTrue(gate.prompts.isEmpty())
        assertTrue(fake.answers.isEmpty())
        approvals.viewGate!!.complete(Unit)
        assertEquals("sha256:shown", model.uiState.value.pinned?.shownDigest)
        assertFalse(model.uiState.value.loading)
        model.load()
        assertEquals(2, approvals.views)
    }

    @Test fun aReadThatFailsNamesTheOrganizationWhenOneIsRequired() = runTest {
        approvals.viewError = AppException.OrganizationRequired()
        val model = request()
        assertTrue(model.uiState.value.orgRequired)
        assertFalse(model.uiState.value.loading)
        assertTrue(model.uiState.value.error != null)
        approvals.viewError = null
        model.load()
        assertFalse(model.uiState.value.orgRequired)
        assertNull(model.uiState.value.error)
    }

    @Test fun aReadInFlightIsCancelledByAnAnswerAndStopsLoading() = runTest {
        val model = request()
        assertEquals("appr_0001", model.uiState.value.approval?.id)
        approvals.viewGate = CompletableDeferred()
        model.load()
        assertTrue(model.uiState.value.loading)
        model.approve("Approve")
        assertEquals(1, fake.answers.size)
        assertFalse(model.uiState.value.loading, "the cancelled read no longer shows as loading")
        assertEquals(ApprovalStatus.APPROVED, model.uiState.value.approval?.status)
    }

    @Test fun nothingIsReadOrAnsweredWhileAnAnswerIsOnItsWay() = runTest {
        val model = request()
        val unlocking = CompletableDeferred<Unit>()
        gate.wait = unlocking
        model.approve("Approve")
        assertEquals(ApprovalAnswer.APPROVE, model.uiState.value.sending)
        val reads = approvals.views
        model.load()
        assertEquals(reads, approvals.views, "its own result is read after it")
        model.approve("Approve")
        assertEquals(1, gate.prompts.size)
        model.setReason("No")
        model.decline("Decline")
        assertEquals(1, gate.prompts.size, "a second answer waits for the first")
        unlocking.complete(Unit)
        assertNull(model.uiState.value.sending)
        assertEquals(1, fake.answers.size)
    }

    @Test fun theConsoleLinkIsNullWhenTheAccountIsGone() = runTest {
        assertNull(request(run.ostrom.app.accounts()).uiState.value.consoleUrl)
    }
}
