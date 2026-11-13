// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.models

/**
 * A line of the organization's activity: [summary] is the server's sentence for a person, [at] its time in epoch
 * milliseconds (null when unreadable). [approvalId] links the approval it is about.
 */
data class ActivityItem(
    val id: String,
    val at: Long?,
    val kind: String,
    val actorKind: String,
    val actorName: String,
    val summary: String,
    val repo: String? = null,
    val costUsd: Double? = null,
    val approvalId: String? = null,
) {
    /** An agent is named as one; a person, or an actor of a kind this app does not know, by their name. */
    val byAgent: Boolean get() = actorKind == "agent"
}

/** A page of activity, newest first; [next] is the cursor of the older page, null on the last one. */
data class ActivityPage(val items: List<ActivityItem>, val next: String?)
