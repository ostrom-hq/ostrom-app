// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteTransferRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SessionTransfer
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferPage
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.repositories.TransferRepository
import run.ostrom.core.api.models.CloudTransfer
import run.ostrom.core.api.models.CreateCloudTransferRequest
import run.ostrom.core.client.OstromException

/** Session transfers over the typed client (`/api/v1/cloud/transfers`, `/cloud/sessions/{id}/transfer`, `/cloud/transfer-recipients`). */
class RemoteTransferRepository(private val calls: AccountCalls) : TransferRepository {
    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer = refusals(accountId) { client ->
        client.cloudTransfers.request(sessionId, CreateCloudTransferRequest(request.toMemberId, request.resume), idempotencyKey).domain()
    }

    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?): TransferPage = calls.withAccount(accountId) { client ->
        val page = client.cloudTransfers.list(direction = direction.wire, before = before, limit = PAGE)
        TransferPage(page.items.map { it.domain() }, page.next)
    }

    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?): SessionTransfer = refusals(accountId) { it.cloudTransfers.accept(transferId, resume).domain() }

    override suspend fun decline(accountId: String, transferId: String): SessionTransfer = refusals(accountId) { it.cloudTransfers.decline(transferId).domain() }

    override suspend fun cancel(accountId: String, transferId: String): SessionTransfer = refusals(accountId) { it.cloudTransfers.cancel(transferId).domain() }

    override suspend fun recipients(accountId: String): List<TeamMember> = refusals(accountId, resource = false) { client ->
        client.cloudTransfers.recipients().recipients.map { TeamMember(it.id, it.login, it.administrator) }
    }

    /**
     * A refusal of the transfer (`403`, `409` or `422` with an error key) keeps its key, which the screens word; a `404`
     * means the session or transfer is gone or hidden. The server's message is never read nor shown.
     */
    private suspend fun <T> refusals(accountId: String, resource: Boolean = true, call: suspend (run.ostrom.core.client.OstromClient) -> T): T =
        calls.withAccount(accountId, resource) { client ->
            try {
                call(client)
            } catch (error: OstromException) {
                val type = error.type
                if (type != null && error.status in REFUSALS) throw AppException.TransferRefused(type)
                throw error
            }
        }

    private companion object {
        const val PAGE = 50
        val REFUSALS = setOf(403, 409, 422)
    }
}

internal fun CloudTransfer.domain() = SessionTransfer(
    id = id,
    sessionId = sessionId,
    fromMemberId = fromMemberId,
    toMemberId = toMemberId,
    state = TransferState.of(state),
    resume = resume,
    createdAt = millisOf(createdAt),
    updatedAt = millisOf(updatedAt),
    expiresAt = millisOf(expiresAt),
    problemCode = failure?.code,
)
