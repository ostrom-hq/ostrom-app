// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.ActionKind
import run.ostrom.app.domain.models.ActionRisk
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAction
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalAsker
import run.ostrom.app.domain.models.ApprovalCheck
import run.ostrom.app.domain.models.ApprovalCommit
import run.ostrom.app.domain.models.ApprovalEvidence
import run.ostrom.app.domain.models.ApprovalGate
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.ApprovalTarget
import run.ostrom.app.domain.models.ApprovalViewer
import run.ostrom.app.domain.models.CheckState
import run.ostrom.app.domain.models.DiffStats
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.core.api.models.ApprovalView
import run.ostrom.core.api.models.ApprovalAnswer as ApiAnswer
import run.ostrom.core.api.models.ApprovalQueue as ApiQueue

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

/** The request as the app shows it; a value of a newer server reads as `UNKNOWN`, an unreadable time as none. */
internal fun ApprovalView.toDomain(): Approval {
    val details = approval.commitDetails.orEmpty()
    val commits = if (details.isNotEmpty()) details.map { ApprovalCommit(it.sha, it.title, it.author) }
    else approval.commits.orEmpty().map { ApprovalCommit(it) }
    return Approval(
        id = approval.id,
        title = approval.title,
        status = approval.status.name.toEnum(ApprovalStatus.UNKNOWN),
        shownDigest = approval.shownDigest,
        requestedFrom = approval.requestedFrom,
        viewer = ApprovalViewer(self = you.self, canApprove = you.canApprove, reasonType = you.reasonType),
        repo = approval.repo,
        sha = approval.sha,
        commits = commits,
        checks = approval.checks.orEmpty().map { ApprovalCheck(it.name, it.state.name.toEnum(CheckState.UNKNOWN)) },
        evidence = approval.evidence.map { ApprovalEvidence(it.label, it.value) },
        askedBy = approval.askedBy.orEmpty(),
        decidedBy = approval.decidedBy,
        decidedAt = approval.decidedAt?.let { RemoteAuthRepository.epochMillis(it) },
        expiresAt = approval.expiresAt?.let { RemoteAuthRepository.epochMillis(it) },
        selfApproved = approval.selfApproved == true,
        action = approval.action?.let {
            ApprovalAction(it.kind.name.toEnum(ActionKind.UNKNOWN), it.repo, it.ref, it.headSha, it.baseSha, it.risk.name.toEnum(ActionRisk.UNKNOWN))
        },
        diffStats = approval.diffStats?.let { DiffStats(it.files, it.additions, it.deletions) },
        diffTruncated = approval.diffTruncated == true,
        who = who?.let { ApprovalAsker(it.dev, it.agent, it.model) },
        what = what?.let { ApprovalTarget(it.action, it.command, it.repo) },
        gate = gate?.let { ApprovalGate(it.workflowName, it.label) },
        note = note,
    )
}

/** The domain's value of the same name, or [unknown]. */
private inline fun <reified E : Enum<E>> String.toEnum(unknown: E): E = enumValues<E>().firstOrNull { it.name == this } ?: unknown
