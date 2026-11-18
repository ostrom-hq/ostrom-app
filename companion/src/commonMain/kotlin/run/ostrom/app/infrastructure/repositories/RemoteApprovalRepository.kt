// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalAsker
import run.ostrom.app.domain.models.ApprovalCommit
import run.ostrom.app.domain.models.ApprovalEvidence
import run.ostrom.app.domain.models.ApprovalGate
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.ApprovalTarget
import run.ostrom.app.domain.models.ApprovalViewer
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.core.api.models.ApprovalView
import run.ostrom.core.api.models.ApprovalAnswer as ApiAnswer
import run.ostrom.core.api.models.ApprovalQueue as ApiQueue
import run.ostrom.core.api.models.ApprovalStatus as ApiStatus

/** [ApprovalRepository] over the typed client's `approvals` and `auth.unlock`; failures translated by [AccountCalls]. */
class RemoteApprovalRepository(private val calls: AccountCalls) : ApprovalRepository {
    override suspend fun queue(accountId: String): ApprovalQueue = calls.withAccount(accountId) { it.approvals.queue().toDomain() }

    override suspend fun view(accountId: String, id: String): Approval =
        calls.withAccount(accountId, resource = true) { it.approvals.view(id).toDomain() }

    override suspend fun answer(accountId: String, id: String, answer: ApprovalAnswer, shownDigest: String, note: String?): Approval =
        calls.withAccount(accountId) {
            val sent = when (answer) {
                ApprovalAnswer.APPROVE -> ApiAnswer.APPROVE
                ApprovalAnswer.REJECT -> ApiAnswer.REJECT
            }
            it.approvals.answer(id, sent, shownDigest, note).toDomain()
        }

    override suspend fun unlock(accountId: String, unlockKey: String, requestId: String?) {
        calls.withAccount(accountId) { it.auth.unlock(unlockKey, requestId) }
    }
}

internal fun ApiQueue.toDomain() = ApprovalQueue(pending.map { it.toDomain() }, recent.map { it.toDomain() })

/**
 * The request as the app shows it; a value of a newer server reads as `UNKNOWN`, an unreadable time as none. The server
 * sends no checks, commit details, gated action, diff size or expiry: those stay empty.
 */
internal fun ApprovalView.toDomain(): Approval {
    return Approval(
        id = approval.id,
        title = approval.title,
        status = approval.status.toDomain(),
        shownDigest = approval.shownDigest,
        requestedFrom = approval.requestedFrom,
        viewer = ApprovalViewer(self = you.self, canApprove = you.canApprove, reasonType = you.reasonType),
        repo = approval.repo,
        sha = approval.sha,
        commits = approval.commits.orEmpty().map { ApprovalCommit(it) },
        evidence = approval.evidence.map { ApprovalEvidence(it.label, it.value) },
        askedBy = approval.askedBy.orEmpty(),
        decidedBy = approval.decidedBy,
        decidedAt = approval.decidedAt?.let { RemoteAuthRepository.epochMillis(it) },
        selfApproved = approval.selfApproved == true,
        who = ApprovalAsker(who.dev),
        what = ApprovalTarget(what.action, what.command, what.repo),
        gate = gate?.let { ApprovalGate(it.workflowName, it.label) },
        note = note,
    )
}

/** The domain's value of the same name, or [unknown]. */
internal fun ApiStatus.toDomain(): ApprovalStatus = when (this) {
    ApiStatus.PENDING -> ApprovalStatus.PENDING
    ApiStatus.APPROVED -> ApprovalStatus.APPROVED
    ApiStatus.REJECTED -> ApprovalStatus.REJECTED
    ApiStatus.EXPIRED -> ApprovalStatus.EXPIRED
    ApiStatus.UNKNOWN -> ApprovalStatus.UNKNOWN
}
