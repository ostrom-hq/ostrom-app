// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../TransferFakes.kt; see NOTICE.
package run.ostrom.app

import run.ostrom.app.domain.models.SessionTransfer
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferPage
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.repositories.TransferRepository

/** [account] as the member [memberId] of its organization. */
fun member(id: String = "a1", memberId: String = "m1") = account(id).copy(organizationId = "o1", memberId = memberId)

fun transfer(id: String, state: TransferState = TransferState.OFFERED, from: String = "m1", to: String = "m2", sessionId: String = "s1", resume: Boolean = false) =
    SessionTransfer(id, sessionId, from, to, state, resume, createdAt = 1_000, updatedAt = 1_000, expiresAt = 2_000_000, problemCode = null)

/** Transfers answered by the lambdas below; every call is recorded in [calls]. */
class FakeTransferRepository : TransferRepository {
    val calls = mutableListOf<String>()
    var recipients = listOf(TeamMember("m2", "grace", administrator = false), TeamMember("m1", "ada", administrator = true))
    var transferResult: (TransferRequest) -> SessionTransfer = { transfer("t1", TransferState.OFFERED, to = it.toMemberId, resume = it.resume) }
    var pages: (TransferDirection, String?) -> TransferPage = { _, _ -> TransferPage(emptyList(), null) }
    var decision: (String, String) -> SessionTransfer = { action, id ->
        transfer(id, when (action) { "accept" -> TransferState.ACCEPTED; "decline" -> TransferState.DECLINED; else -> TransferState.CANCELLED })
    }

    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer {
        calls += "transfer $sessionId ${request.toMemberId} resume=${request.resume} $idempotencyKey"
        return transferResult(request)
    }

    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?): TransferPage {
        calls += "list ${direction.wire} $before"
        return pages(direction, before)
    }

    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?) = decision("accept", transferId).also { calls += "accept $transferId $resume" }
    override suspend fun decline(accountId: String, transferId: String) = decision("decline", transferId).also { calls += "decline $transferId" }
    override suspend fun cancel(accountId: String, transferId: String) = decision("cancel", transferId).also { calls += "cancel $transferId" }
    override suspend fun recipients(accountId: String): List<TeamMember> { calls += "recipients"; return recipients }
}

