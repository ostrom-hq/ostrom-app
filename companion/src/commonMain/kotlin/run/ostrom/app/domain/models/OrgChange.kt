// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionChange (Apache-2.0), companion/.../domain/models/SessionChange.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * One event of the organization's feed (`GET /api/v1/events`, `events.v1`), as far as the app reads it: its [type],
 * what it is about ([subjectId]) and the member it belongs to ([memberId], null for the whole organization's).
 */
data class OrgChange(val type: String, val subjectId: String, val memberId: String?) {
    companion object {
        const val WAIT_CREATED = "cloud.wait.created"
        const val WAIT_RESOLVED = "cloud.wait.resolved"
        const val WAIT_CANCELLED = "cloud.wait.cancelled"

        /** A wait asked, answered or withdrawn. */
        val WAIT_TYPES = setOf(WAIT_CREATED, WAIT_RESOLVED, WAIT_CANCELLED)
    }
}
