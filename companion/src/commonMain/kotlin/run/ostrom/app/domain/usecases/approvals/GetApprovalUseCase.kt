// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.usecases.approvals

import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** One request, read again each time the screen shows it; a `401` checks the account once. */
class GetApprovalUseCase(private val approvals: ApprovalRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, id: String): Approval = guard(accountId) { approvals.view(accountId, id) }
}
