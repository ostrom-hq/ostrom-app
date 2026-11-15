// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/Transfer.kt; see NOTICE.
package run.ostrom.app.domain.models

/** The server's transfer states (`runners.cloud.transfer.v1`); [UNKNOWN] for a state a newer server added. */
enum class TransferState(val wire: String) {
    OFFERED("offered"),
    ACCEPTED("accepted"),
    COMPLETED("completed"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    ;

    /** Still waiting for a decision ([OFFERED]) or for the owner change ([ACCEPTED]). */
    val open: Boolean get() = this == OFFERED || this == ACCEPTED

    companion object {
        fun of(wire: String?): TransferState = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** A change of owner of a session: an administrator's transfer (accepted at once) or an owner's offer. */
data class SessionTransfer(
    val id: String,
    val sessionId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val state: TransferState,
    /** The session resumes for the recipient once it is theirs. */
    val resume: Boolean,
    val createdAt: Long?,
    val updatedAt: Long?,
    /** Offers: the recipient must accept before this time. */
    val expiresAt: Long?,
    /** Why it failed or was cancelled by the server (`recipient_unavailable`, `transfer_timeout`, ...). */
    val problemCode: String?,
)

data class TransferPage(val transfers: List<SessionTransfer>, val nextBefore: String?)

/** Which transfers a list shows, from the signed-in member's side. */
enum class TransferDirection(val wire: String) { INCOMING("incoming"), OUTGOING("outgoing") }

/** A member a session can be given to, as the recipients' list shows them (by login). */
data class TeamMember(
    val id: String,
    val username: String,
    val administrator: Boolean,
)

/** What the transfer form sends: [resume] starts the session for the recipient once it is theirs. */
data class TransferRequest(val toMemberId: String, val resume: Boolean = false)

/** A field of the transfer form that breaks a rule: nothing was sent. */
enum class TransferProblem {
    /** No recipient is chosen, or the identifier is not one the server takes. */
    RECIPIENT,

    /** The recipient already owns the session. */
    OWNER,
}
