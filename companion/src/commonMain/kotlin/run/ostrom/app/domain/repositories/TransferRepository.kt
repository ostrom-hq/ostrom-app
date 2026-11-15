// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/TransferRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.SessionTransfer
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferPage
import run.ostrom.app.domain.models.TransferRequest

/**
 * Session transfers of an account's server (`runners.cloud.transfer.v1`). Failures are
 * [run.ostrom.app.domain.models.AppException]s; a refusal of the transfer is
 * [run.ostrom.app.domain.models.AppException.TransferRefused] with its error key.
 */
interface TransferRepository {
    /** [idempotencyKey]: a retry with the same key and body returns the transfer the first attempt made. */
    suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer

    /** Newest first, at most 50 per page; [before] is the previous page's cursor. */
    suspend fun transfers(accountId: String, direction: TransferDirection, before: String?): TransferPage

    /** [resume] null keeps what the offer asked for. */
    suspend fun accept(accountId: String, transferId: String, resume: Boolean?): SessionTransfer

    suspend fun decline(accountId: String, transferId: String): SessionTransfer

    suspend fun cancel(accountId: String, transferId: String): SessionTransfer

    /** The members a session can be given to, for whoever may transfer. */
    suspend fun recipients(accountId: String): List<TeamMember>
}
