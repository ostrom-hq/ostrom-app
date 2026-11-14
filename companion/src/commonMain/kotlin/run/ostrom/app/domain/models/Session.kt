// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's CloudSession (Apache-2.0), companion/.../domain/models/CloudSession.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * One coding agent session (a segment of it) as the server records it (`sessions.v1`): where it ran ([repo]), which
 * agent and [model], who ran it ([dev]), when it started and ended (epoch milliseconds, null when unreadable), and
 * what it cost. [endedAt] null means it is still open; [lost] means the launcher stopped answering before it ended.
 * [receiptId] is the signed receipt of the session, [runId] the workflow run it worked for.
 */
data class AgentSession(
    val id: String,
    val repo: String?,
    val agent: String,
    val model: String?,
    val dev: String,
    val startedAt: Long?,
    val endedAt: Long?,
    val endedBy: String?,
    val lastSeenAt: Long?,
    val tools: Int?,
    val costUsd: Double?,
    val exitCode: Int?,
    val segment: Int?,
    val receiptId: String?,
    val runId: String?,
    val lost: Boolean,
    /** `working` or `waiting_for_human` while open, when the agent reports it; null otherwise. */
    val activity: String?,
    /** On a single read: the answers to the session's gates, oldest gate first. Empty in a list. */
    val gateAnswers: List<SessionGateAnswer> = emptyList(),
) {
    val open: Boolean get() = endedAt == null && !lost
}

/** An answer to one of a session's gates: the [outcome] (`approved`, `denied`...) [by] whom, [at] when. */
data class SessionGateAnswer(val gateId: String, val actionClass: String, val outcome: String, val by: String, val at: Long?, val receiptId: String?)

/** A page of sessions, newest first; [next] is the cursor of the older page, null on the last one. */
data class SessionPage(val sessions: List<AgentSession>, val next: String?)

/** Which sessions the list shows: all of them, the open ones, or the ended ones (the server's `open` filter). */
enum class SessionFilter(val open: Boolean?) { ALL(null), OPEN(true), ENDED(false) }
