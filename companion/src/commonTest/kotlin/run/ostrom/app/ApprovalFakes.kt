// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app

import run.ostrom.app.domain.models.ActionKind
import run.ostrom.app.domain.models.ActionRisk
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAction
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalAsker
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.ApprovalTarget
import run.ostrom.app.domain.models.ApprovalViewer
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome

/** A high-risk push waiting for the person's answer. */
fun pendingApproval(
    id: String = "appr_0001",
    risk: ActionRisk = ActionRisk.HIGH,
    digest: String = "sha256:shown",
    viewer: ApprovalViewer = ApprovalViewer(),
    diffTruncated: Boolean = false,
    command: String = "git push origin main",
) = Approval(
    id = id,
    title = "Push to main",
    status = ApprovalStatus.PENDING,
    shownDigest = digest,
    requestedFrom = "ada",
    viewer = viewer,
    repo = "acme/api",
    action = ApprovalAction(ActionKind.PUSH, "acme/api", "main", "c0ffee0123", risk = risk),
    diffTruncated = diffTruncated,
    who = ApprovalAsker(dev = "grace", agent = "claude-code", model = "opus"),
    what = ApprovalTarget(command = command),
)

/** A biometric gate whose answers a test sets; [prompts] records what it asked. */
class FakeBiometricGate(var outcome: BiometricOutcome = BiometricOutcome.CONFIRMED, override val supported: Boolean = true) : BiometricGate {
    val prompts = mutableListOf<String>()

    override suspend fun confirm(prompt: String): BiometricOutcome {
        prompts += prompt
        return outcome
    }
}

/** An [ApprovalRepository] whose answers a test sets, recording the answers and unlocks it was sent. */
class FakeApprovalRepository : ApprovalRepository {
    var queue = ApprovalQueue(listOf(pendingApproval()), emptyList())
    var queueError: Exception? = null
    var view: Approval = pendingApproval()
    var answerError: Exception? = null
    var unlockError: Exception? = null
    /** Runs during the unlock call, before it answers (a sign-out in the middle). */
    var duringUnlock: suspend () -> Unit = {}
    val answers = mutableListOf<Answer>()
    val unlocks = mutableListOf<Pair<String, String?>>()

    data class Answer(val id: String, val answer: ApprovalAnswer, val shownDigest: String, val note: String?)

    override suspend fun queue(accountId: String): ApprovalQueue {
        queueError?.let { throw it }
        return queue
    }

    override suspend fun view(accountId: String, id: String): Approval = view

    override suspend fun answer(accountId: String, id: String, answer: ApprovalAnswer, shownDigest: String, note: String?): Approval {
        answerError?.let { throw it }
        answers += Answer(id, answer, shownDigest, note)
        return view.copy(status = if (answer == ApprovalAnswer.APPROVE) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED, decidedBy = "ada", note = note)
    }

    override suspend fun unlock(accountId: String, unlockKey: String, requestId: String?) {
        unlocks += unlockKey to requestId
        duringUnlock()
        unlockError?.let { throw it }
    }
}
