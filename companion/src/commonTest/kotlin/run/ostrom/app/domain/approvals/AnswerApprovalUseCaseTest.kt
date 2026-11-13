// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.approvals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeApprovalRepository
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeBiometricGate
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.ActionRisk
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.ApprovalViewer
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.domain.models.NoApprove
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.domain.usecases.approvals.AnswerApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.AnswerResult
import run.ostrom.app.domain.usecases.approvals.GetApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.LoadApprovalsUseCase
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.pendingApproval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswerApprovalUseCaseTest {
    private val credentials = MemoryCredentialStore()
    private val approvals = FakeApprovalRepository()
    private val gate = FakeBiometricGate()
    private val clock = FixedClock()
    private val accounts = accounts().also { it.save(account()) }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials, clock))
    private val answer = AnswerApprovalUseCase(approvals, PhoneUnlock(credentials, approvals, gate, clock), guard)

    private suspend fun keep() {
        credentials.write("a1", "osm_token")
        credentials.write(unlockKeyEntry("a1"), "key-1")
    }

    @Test
    fun aYesIsBoundToTheDigestReadAndUnlockedForItsRequest() = runTest {
        keep()
        val result = answer("a1", pendingApproval(digest = "d-read"), ApprovalAnswer.APPROVE, note = "ignored", prompt = "Approve “Push to main”")
        assertEquals(ApprovalStatus.APPROVED, assertIs<AnswerResult.Answered>(result).approval.status)
        assertEquals(FakeApprovalRepository.Answer("appr_0001", ApprovalAnswer.APPROVE, "d-read", null), approvals.answers.single())
        assertEquals("key-1" to "appr_0001", approvals.unlocks.single())
        assertEquals(listOf("Approve “Push to main”"), gate.prompts)
    }

    @Test
    fun aNoNeedsAReasonAndSendsItTrimmed() = runTest {
        keep()
        assertEquals(AnswerResult.NothingSent, answer("a1", pendingApproval(), ApprovalAnswer.REJECT, note = "   ", prompt = "p"))
        assertEquals(AnswerResult.NothingSent, answer("a1", pendingApproval(), ApprovalAnswer.REJECT, note = null, prompt = "p"))
        assertTrue(gate.prompts.isEmpty(), "No prompt without a reason")
        answer("a1", pendingApproval(), ApprovalAnswer.REJECT, note = "  Wait for the migration PR ", prompt = "p")
        assertEquals("Wait for the migration PR", approvals.answers.single().note)
        assertEquals("key-1" to null, approvals.unlocks.single(), "A no unlocks for no request")
    }

    @Test
    fun noYesOnAChangeNotShownInFullOrRefusedByTheServerNorOnAnAnsweredRequest() = runTest {
        keep()
        val truncated = pendingApproval(diffTruncated = true)
        val refused = pendingApproval(viewer = ApprovalViewer(canApprove = false))
        assertEquals(NoApprove.TRUNCATED, truncated.noApprove)
        assertEquals(NoApprove.REFUSED, refused.copy(diffTruncated = true).noApprove)
        for (approval in listOf(truncated, refused, pendingApproval().copy(status = ApprovalStatus.APPROVED))) {
            assertEquals(AnswerResult.NothingSent, answer("a1", approval, ApprovalAnswer.APPROVE, null, "p"))
        }
        assertTrue(gate.prompts.isEmpty())
        assertTrue(approvals.answers.isEmpty())
        // The no stays possible.
        assertIs<AnswerResult.Answered>(answer("a1", truncated, ApprovalAnswer.REJECT, "Too big", "p"))
    }

    @Test
    fun aLowRiskNoAfterAFreshUnlockAsksNothing() = runTest {
        keep()
        val low = pendingApproval(risk = ActionRisk.LOW)
        assertFalse(low.needsBiometrics)
        answer("a1", low, ApprovalAnswer.REJECT, "no", "p")
        answer("a1", low, ApprovalAnswer.REJECT, "no", "p")
        assertEquals(1, gate.prompts.size)
        assertTrue(pendingApproval(risk = ActionRisk.UNKNOWN).needsBiometrics)
    }

    @Test
    fun aCancelledPromptSendsNothingAndSaysNothing() = runTest {
        keep()
        gate.outcome = BiometricOutcome.CANCELLED
        assertEquals(AnswerResult.NothingSent, answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
        gate.outcome = BiometricOutcome.UNAVAILABLE
        assertEquals(AnswerResult.Failed(DecisionProblem.BIOMETRICS_UNAVAILABLE), answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
        gate.outcome = BiometricOutcome.FAILED
        assertEquals(AnswerResult.Failed(DecisionProblem.BIOMETRICS_FAILED), answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
        assertTrue(approvals.answers.isEmpty())
    }

    @Test
    fun aRefusedKeySaysToSignInAgainAndAnOfflineUnlockSaysNothingWasSent() = runTest {
        keep()
        approvals.unlockError = AppException.Server(403, "unlock_refused")
        assertEquals(AnswerResult.Failed(DecisionProblem.UNLOCK_REFUSED), answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
        approvals.unlockError = AppException.Unreachable()
        assertEquals(AnswerResult.Failed(DecisionProblem.UNLOCK_OFFLINE), answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
        approvals.unlockError = AppException.Server(500, null)
        assertEquals(AnswerResult.Failed(DecisionProblem.FAILED), answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
        assertTrue(approvals.answers.isEmpty())
    }

    @Test
    fun theAnswersRefusalIsWorded() = runTest {
        keep()
        approvals.answerError = AppException.StepUpRequired(requestBound = true)
        assertEquals(AnswerResult.Failed(DecisionProblem.UNLOCK_AGAIN), answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
        approvals.answerError = AppException.Server(409, "shown_digest_mismatch")
        assertEquals(AnswerResult.Failed(DecisionProblem.CHANGED), answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p"))
    }

    @Test
    fun cancellationIsNeverSwallowed() = runTest {
        keep()
        approvals.unlockError = CancellationException("left")
        assertFailsWith<CancellationException> { answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p") }
        approvals.unlockError = null
        approvals.answerError = CancellationException("left")
        assertFailsWith<CancellationException> { answer("a1", pendingApproval(), ApprovalAnswer.APPROVE, null, "p") }
    }

    @Test
    fun theInboxAndARequestAreRead() = runTest {
        approvals.queue = ApprovalQueue(listOf(pendingApproval()), listOf(pendingApproval(id = "appr_0000").copy(status = ApprovalStatus.APPROVED)))
        val queue = LoadApprovalsUseCase(approvals, guard)("a1")
        assertEquals(listOf("appr_0001"), queue.pending.map { it.id })
        assertEquals(listOf("appr_0000"), queue.recent.map { it.id })
        assertEquals("appr_0001", GetApprovalUseCase(approvals, guard)("a1", "appr_0001").id)
        assertNull(pendingApproval().noApprove)
    }
}
