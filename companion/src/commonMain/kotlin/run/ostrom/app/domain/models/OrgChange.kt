// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionChange (Apache-2.0), companion/.../domain/models/SessionChange.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * One event of the organization's feed (`GET /api/v1/events`, `events.v1`), as far as the app reads it: its [type],
 * what it is about ([subjectId]: a wait's id, a run's id, a session's id) and the member it belongs to ([memberId],
 * null for the whole organization's). [workflowId] is the workflow of a run event (its `data.workflowId`), null for the
 * other types.
 */
data class OrgChange(val type: String, val subjectId: String, val memberId: String?, val workflowId: String? = null) {
    companion object {
        const val WAIT_CREATED = "cloud.wait.created"
        const val WAIT_RESOLVED = "cloud.wait.resolved"
        const val WAIT_CANCELLED = "cloud.wait.cancelled"

        /** A workflow run moved (`subject` `workflow_run`, data `{runId, workflowId, status, ownerId, parentRunId?}`). */
        const val RUN_UPDATED = "cloud.workflow.run.updated"

        /** A session's todo changed (`subject` `session`, data `{sessionId, status, done, total}`). */
        const val TODO_UPDATED = "cloud.todo"

        /** A wait asked, answered or withdrawn. */
        val WAIT_TYPES = setOf(WAIT_CREATED, WAIT_RESOLVED, WAIT_CANCELLED)

        /**
         * The types the app reads from a server that announces [features]: the union its screens follow, each only
         * where the server has the feature (ostrom-server knows each of them; it has no session event type).
         */
        fun typesFor(features: Set<String>): Set<String> = buildSet {
            if (Features.WAITS in features) addAll(WAIT_TYPES)
            if (Features.WORKFLOWS in features) add(RUN_UPDATED)
            if (Features.TODOS in features) add(TODO_UPDATED)
        }
    }
}
