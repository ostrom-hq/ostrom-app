// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion demo routers (Apache-2.0); see NOTICE.
package run.ostrom.app.demo

import kotlinx.coroutines.flow.update
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.domain.services.Clock

/** The demo's inbox, in memory: an answer moves its request from what waits to what was answered. */
class DemoApprovalRepository(private val demo: DemoMode, private val clock: Clock) : ApprovalRepository {
    override suspend fun queue(accountId: String): ApprovalQueue = demo.store().approvals.value

    override suspend fun view(accountId: String, id: String): Approval = find(id) ?: throw AppException.NotFound()

    override suspend fun answer(accountId: String, id: String, answer: ApprovalAnswer, shownDigest: String, note: String?): Approval {
        val approval = find(id) ?: throw AppException.NotFound()
        if (!approval.open) throw AppException.Server(CONFLICT, "conflict")
        if (approval.shownDigest != shownDigest) throw AppException.Server(CONFLICT, "shown_digest_mismatch")
        val status = if (answer == ApprovalAnswer.APPROVE) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
        val answered = approval.copy(status = status, decidedBy = demo.store().account.value.login, decidedAt = clock.nowMillis(), note = note)
        demo.store().approvals.update { queue -> ApprovalQueue(queue.pending.filter { it.id != id }, listOf(answered) + queue.recent) }
        return answered
    }

    /** The demo's unlock key is no key: there is no server to prove anything to. */
    override suspend fun unlock(accountId: String, unlockKey: String, requestId: String?) = Unit

    private fun find(id: String): Approval? = demo.store().approvals.value.let { it.pending + it.recent }.firstOrNull { it.id == id }

    private companion object {
        const val CONFLICT = 409
    }
}

/** The demo account's approvals from memory, every other account's from its server. */
class DemoAwareApprovalRepository(private val real: ApprovalRepository, private val demo: ApprovalRepository) : ApprovalRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real

    override suspend fun queue(accountId: String) = of(accountId).queue(accountId)
    override suspend fun view(accountId: String, id: String) = of(accountId).view(accountId, id)
    override suspend fun answer(accountId: String, id: String, answer: ApprovalAnswer, shownDigest: String, note: String?) =
        of(accountId).answer(accountId, id, answer, shownDigest, note)
    override suspend fun unlock(accountId: String, unlockKey: String, requestId: String?) = of(accountId).unlock(accountId, unlockKey, requestId)
}

/**
 * The platform's biometrics, except while the demo runs: the demo always confirms, so its answers go without Face ID.
 * A demo never runs beside a real account, so no real answer ever goes through here unchecked.
 */
class DemoAwareBiometricGate(private val real: BiometricGate, private val demo: DemoMode) : BiometricGate {
    override val supported: Boolean get() = demo.active || real.supported

    override suspend fun confirm(prompt: String): BiometricOutcome = if (demo.active) BiometricOutcome.CONFIRMED else real.confirm(prompt)
}
