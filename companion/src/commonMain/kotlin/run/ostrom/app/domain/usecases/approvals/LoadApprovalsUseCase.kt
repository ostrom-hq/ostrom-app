// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.usecases.approvals

import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** The inbox: what waits for the person's answer and what was answered lately; a `401` checks the account once. */
class LoadApprovalsUseCase(private val approvals: ApprovalRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): ApprovalQueue = guard(accountId) { approvals.queue(accountId) }
}
