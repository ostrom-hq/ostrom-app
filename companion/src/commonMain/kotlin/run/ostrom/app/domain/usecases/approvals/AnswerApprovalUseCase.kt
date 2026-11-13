// SPDX-License-Identifier: Apache-2.0
// The answer of the React Native app's useRequestViewModel.ts, in Kotlin.
package run.ostrom.app.domain.usecases.approvals

import kotlinx.coroutines.CancellationException
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.domain.models.UnlockOutcome
import run.ostrom.app.domain.models.decisionProblem
import run.ostrom.app.domain.models.problem
import run.ostrom.app.domain.models.unlockProblem
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** How an answer ended. */
sealed interface AnswerResult {
    /** The server took it: the request as it is now. */
    data class Answered(val approval: Approval) : AnswerResult

    /** Nothing was sent and there is nothing to say: the person cancelled the prompt, or no answer was possible. */
    data object NothingSent : AnswerResult

    /** It did not go through, for [problem]. */
    data class Failed(val problem: DecisionProblem) : AnswerResult
}

/**
 * Answers a request as the person read it: [approval] is the reading they saw first, and its digest is what the answer
 * is bound to (never data a refresh swapped in). Nothing is sent for a request that no longer waits, for a yes the app
 * does not take (the change not shown in full, a yes the server refuses) or for a no without a reason. Then the device
 * proves its person is there ([PhoneUnlock]: a yes unlocks for its own request; biometrics unless the action is low
 * risk), and only then the answer leaves.
 */
class AnswerApprovalUseCase(
    private val approvals: ApprovalRepository,
    private val unlock: PhoneUnlock,
    private val guard: AccountGuard,
) {
    /**
     * @param note The reason of a no, which the agent reads; trimmed, and required with [ApprovalAnswer.REJECT].
     * @param prompt What the biometric prompt says.
     */
    suspend operator fun invoke(accountId: String, approval: Approval, answer: ApprovalAnswer, note: String?, prompt: String): AnswerResult {
        val reason = note?.trim()?.takeIf { it.isNotEmpty() }
        val allowed = approval.open && when (answer) {
            ApprovalAnswer.APPROVE -> approval.mayApprove
            ApprovalAnswer.REJECT -> reason != null
        }
        if (!allowed) return AnswerResult.NothingSent
        val outcome = try {
            guard(accountId) {
                unlock.unlock(accountId, prompt, always = approval.needsBiometrics, requestId = approval.id.takeIf { answer == ApprovalAnswer.APPROVE })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The unlock comes first: nothing was sent, whatever went wrong.
            return AnswerResult.Failed(error.unlockProblem())
        }
        if (outcome != UnlockOutcome.CONFIRMED) return outcome.problem()?.let { AnswerResult.Failed(it) } ?: AnswerResult.NothingSent
        return try {
            val sent = if (answer == ApprovalAnswer.REJECT) reason else null
            AnswerResult.Answered(guard(accountId) { approvals.answer(accountId, approval.id, answer, approval.shownDigest, sent) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AnswerResult.Failed(error.decisionProblem())
        }
    }
}
