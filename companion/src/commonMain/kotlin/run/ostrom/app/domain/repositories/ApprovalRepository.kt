// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalQueue

/** The person's requests for a yes (`/approvals`) and the device's unlock. Failures are `AppException`s. */
interface ApprovalRepository {
    /** What waits for the person's answer, and what was answered lately (`GET /approvals/queue`). */
    suspend fun queue(accountId: String): ApprovalQueue

    /** One request as the app shows it (`GET /approvals/{id}/view`); a request the person cannot see is `NotFound`. */
    suspend fun view(accountId: String, id: String): Approval

    /**
     * Answers [id] (`POST /approvals/{id}/answer`), bound to [shownDigest], the digest of what the person read.
     *
     * @param note What the approver tells the agent; required with a no.
     * @return The request as it is after the answer.
     */
    suspend fun answer(accountId: String, id: String, answer: ApprovalAnswer, shownDigest: String, note: String?): Approval

    /**
     * Records the device's biometric unlock with the key the sign-in gave (`POST /auth/mobile/unlock`); [requestId]
     * binds it to the request a yes is for.
     */
    suspend fun unlock(accountId: String, unlockKey: String, requestId: String?)
}
