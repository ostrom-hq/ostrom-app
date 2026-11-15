// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/transfers/TransferUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.transfers

import kotlinx.coroutines.CancellationException
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SessionTransfer
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferPage
import run.ostrom.app.domain.models.TransferProblem
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.TransferRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** The server's transfer rules the screens check before asking, so an action that would be refused is not offered. */
object TransferRules {
    /** Member identifiers and logins as the server takes them. */
    private val IDENTIFIER = Regex("[a-zA-Z0-9-]{1,64}")

    fun validId(value: String): Boolean = IDENTIFIER.matches(value)

    /**
     * A cloud session (never one on a member's device) its owner may give away: not being created (it runs with the
     * owner's recipe), not ended, not already changing owner.
     */
    fun canTransfer(account: Account, session: CloudSession): Boolean =
        session.ownerId != null && session.ownerId == account.memberId && !session.remote && !session.local && session.status in TRANSFERABLE

    private val TRANSFERABLE = setOf(CloudSessionStatus.RUNNING, CloudSessionStatus.PAUSING, CloudSessionStatus.PAUSED, CloudSessionStatus.RESUMING)

    /** What [account] may do with [transfer]: the recipient decides an offer and its owner withdraws it; nobody else's transfer opens. */
    fun actions(account: Account, transfer: SessionTransfer): Set<TransferAction> = buildSet {
        val me = account.memberId
        if (transfer.state == TransferState.OFFERED && transfer.toMemberId == me) {
            add(TransferAction.ACCEPT)
            add(TransferAction.DECLINE)
        }
        if (transfer.state == TransferState.OFFERED && transfer.fromMemberId == me) add(TransferAction.CANCEL)
    }
}

enum class TransferAction { ACCEPT, DECLINE, CANCEL }

/** Whether the transfer screens show: a signed-in account on a server that announces transfers. */
class TransferAvailabilityUseCase(private val accounts: AccountRepository, private val features: FeaturesRepository) {
    /** The transfers list (incoming and outgoing offers). */
    suspend fun available(accountId: String): Boolean {
        val account = accounts.find(accountId) ?: return false
        return account.signedIn && announced(accountId)
    }

    /** The "Transfer" action of [session]: [TransferRules.canTransfer] on a server with transfers. */
    suspend fun canTransfer(accountId: String, session: CloudSession): Boolean {
        val account = accounts.find(accountId) ?: return false
        return account.signedIn && TransferRules.canTransfer(account, session) && announced(accountId)
    }

    private suspend fun announced(accountId: String): Boolean = try {
        Features.RUNNERS_CLOUD_TRANSFER in features.capabilities(accountId).features
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Unknown for now (offline): hidden rather than offered and refused.
        false
    }
}

/** Who the session can go to, the session's owner left out, by login. */
class LoadRecipientsUseCase(
    private val transfers: TransferRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String, ownerId: String): List<TeamMember> = guard(accountId) {
        if (accounts.find(accountId) == null) throw AppException.Unauthorized()
        transfers.recipients(accountId).filter { it.id != ownerId }.sortedBy { it.username.lowercase() }
    }
}

/**
 * Transfers a session: at once when the server accepts it (an administrator's), as an offer the recipient accepts
 * otherwise; [TransferRequest.resume] asks to start it for the recipient once it is theirs. The caller keeps
 * [idempotencyKey] while the form is unchanged, so a retry after a network failure never transfers twice.
 */
class TransferSessionUseCase(
    private val transfers: TransferRepository,
    private val features: FeaturesRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String, sessionId: String, ownerId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer = guard(accountId) {
        if (accounts.find(accountId) == null) throw AppException.Unauthorized()
        val sent = sent(request)
        if (!TransferRules.validId(sent.toMemberId)) throw AppException.TransferInvalid(TransferProblem.RECIPIENT)
        if (sent.toMemberId == ownerId) throw AppException.TransferInvalid(TransferProblem.OWNER)
        if (Features.RUNNERS_CLOUD_TRANSFER !in features.capabilities(accountId).features) throw AppException.Unsupported(Features.RUNNERS_CLOUD_TRANSFER)
        transfers.transfer(accountId, sessionId, sent, idempotencyKey)
    }

    companion object {
        /** What the server receives: the identifier trimmed. */
        fun sent(request: TransferRequest): TransferRequest = request.copy(toMemberId = request.toMemberId.trim())
    }
}

/** The signed-in member's incoming or outgoing transfers, newest first, and the decisions on them. */
class TransferListUseCase(
    private val transfers: TransferRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend fun page(accountId: String, direction: TransferDirection, before: String? = null): TransferPage = guard(accountId) { transfers.transfers(accountId, direction, before) }

    /**
     * Member logins by ID, in one request, from the recipients' list (members who can no longer receive a session show
     * by ID). The server lists them to whoever may transfer; for another member it refuses, and the list shows IDs.
     */
    suspend fun names(accountId: String): Map<String, String> = guard(accountId) {
        if (accounts.find(accountId) == null) return@guard emptyMap()
        transfers.recipients(accountId).associate { it.id to it.username }
    }

    suspend fun accept(accountId: String, transferId: String, resume: Boolean? = null): SessionTransfer = guard(accountId) { transfers.accept(accountId, transferId, resume) }
    suspend fun decline(accountId: String, transferId: String): SessionTransfer = guard(accountId) { transfers.decline(accountId, transferId) }
    suspend fun cancel(accountId: String, transferId: String): SessionTransfer = guard(accountId) { transfers.cancel(accountId, transferId) }
}
