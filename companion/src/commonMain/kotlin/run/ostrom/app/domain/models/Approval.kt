// SPDX-License-Identifier: Apache-2.0
// The rules of the React Native app's approvals contract (needsBiometrics, phoneMayApprove), in Kotlin.
package run.ostrom.app.domain.models

/** Where a request for a yes stands. [UNKNOWN] is a state of a newer server: no answer is offered from the app. */
enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, UNKNOWN }

/** What a yes lets through; the Approve button is named after it. */
enum class ActionKind { PUSH, MERGE, DEPLOY, TOOL, OTHER, UNKNOWN }

/** The risk of the action: anything but [LOW] needs the biometrics for every answer. */
enum class ActionRisk { LOW, HIGH, UNKNOWN }

/** The state of a CI check shown as evidence. */
enum class CheckState { SUCCESS, FAILURE, PENDING, SKIPPED, UNKNOWN }

/** A yes or a no. */
enum class ApprovalAnswer { APPROVE, REJECT }

/**
 * The action a yes lets through.
 *
 * @property ref The branch or ref it lands on.
 * @property headSha The commit that lands.
 */
data class ApprovalAction(
    val kind: ActionKind,
    val repo: String,
    val ref: String,
    val headSha: String,
    val baseSha: String? = null,
    val risk: ActionRisk,
)

/** The size of the change the yes covers. */
data class DiffStats(val files: Int, val additions: Int, val deletions: Int)

/** A commit the yes covers; [title] and [author] when the server gives them. */
data class ApprovalCommit(val sha: String, val title: String? = null, val author: String? = null)

/** A CI check of the commit, shown as evidence. */
data class ApprovalCheck(val name: String, val state: CheckState)

/** A line of evidence the server shows with the request. */
data class ApprovalEvidence(val label: String, val value: String)

/** Who asks: the developer, their agent and its model. */
data class ApprovalAsker(val dev: String? = null, val agent: String? = null, val model: String? = null)

/** What the yes is for: the action and the command the agent will run. */
data class ApprovalTarget(val action: String? = null, val command: String? = null, val repo: String? = null)

/** The gate of a workflow that asks for the yes. */
data class ApprovalGate(val workflowName: String, val label: String)

/**
 * What the person looking at the request may do with it.
 *
 * @property self The person asked for it themselves.
 * @property canApprove The server takes their yes (a locked phone still reads true: its yes is refused at the answer).
 * @property reasonType The key of why not, when the server names one; its free text is never shown.
 */
data class ApprovalViewer(val self: Boolean = false, val canApprove: Boolean = true, val reasonType: String? = null)

/** Why the app takes no yes on a request; a no stays possible. */
enum class NoApprove {
    /** The whole change is not shown on the device: the yes is given in the console. */
    TRUNCATED,

    /** The server does not take this person's yes. */
    REFUSED,
}

/**
 * What the person read: the server's digest of the request, and what the screen shows around it (who asks, the
 * command, the gate), which a refresh could change under the same digest. A yes is bound to the reading first shown.
 */
data class ApprovalReading(
    val shownDigest: String,
    val who: ApprovalAsker?,
    val what: ApprovalTarget?,
    val gate: ApprovalGate?,
)

/**
 * A request for a yes, as the app shows it (`GET /approvals/{id}/view`): the request, who asks, the gate, the commits,
 * checks and evidence, the answer once given.
 *
 * @property shownDigest The digest of what is shown: the answer carries it, so a yes never covers a changed request.
 * @property requestedFrom The approver asked.
 * @property askedBy Who asked (the session's developer, the pull request's author).
 * @property decidedAt When it was answered (epoch milliseconds), when the server says.
 * @property expiresAt When it expires (epoch milliseconds), when the server says.
 * @property diffTruncated The device is not shown the whole change: no yes from the app.
 * @property note What the approver told the agent with the answer.
 */
data class Approval(
    val id: String,
    val title: String,
    val status: ApprovalStatus,
    val shownDigest: String,
    val requestedFrom: String,
    val viewer: ApprovalViewer,
    val repo: String? = null,
    val sha: String? = null,
    val commits: List<ApprovalCommit> = emptyList(),
    val checks: List<ApprovalCheck> = emptyList(),
    val evidence: List<ApprovalEvidence> = emptyList(),
    val askedBy: List<String> = emptyList(),
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val expiresAt: Long? = null,
    val selfApproved: Boolean = false,
    val action: ApprovalAction? = null,
    val diffStats: DiffStats? = null,
    val diffTruncated: Boolean = false,
    val who: ApprovalAsker? = null,
    val what: ApprovalTarget? = null,
    val gate: ApprovalGate? = null,
    val note: String? = null,
) {
    /** It waits for an answer. */
    val open: Boolean get() = status == ApprovalStatus.PENDING

    /** Every answer asks the biometrics, unless the server says the action is low risk. */
    val needsBiometrics: Boolean get() = action?.risk != ActionRisk.LOW

    /** Why the app takes no yes here: the server's word first, then the change not shown in full. */
    val noApprove: NoApprove?
        get() = when {
            !viewer.canApprove -> NoApprove.REFUSED
            diffTruncated -> NoApprove.TRUNCATED
            else -> null
        }

    val mayApprove: Boolean get() = noApprove == null

    val reading: ApprovalReading get() = ApprovalReading(shownDigest, who, what, gate)
}

/** The person's requests: those that wait for their answer, and those answered lately. */
data class ApprovalQueue(val pending: List<Approval>, val recent: List<Approval>)
