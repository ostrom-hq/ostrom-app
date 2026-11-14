// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion navigation/Destinations.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.navigation

import kotlinx.serialization.Serializable

/** Enter a server origin and check it is an Ostrom server. */
@Serializable
object AddServerRoute

/** GitHub sign-in on [origin]; [accountId] is set when an existing account signs in again. */
@Serializable
data class LoginRoute(val origin: String, val accountId: String? = null)

/** The tabs of a signed-in app: the features the active account's server serves, and Settings. */
@Serializable
object MainRoute

/**
 * One request for a yes of [accountId]'s server: opened from the inbox, and by id from a push or a link. The request is
 * read again from the server each time; an answer goes only from this screen.
 */
@Serializable
data class RequestRoute(val accountId: String, val id: String)

/** The notification preferences of an account (`notifications.v1`), opened from Settings. */
@Serializable
data class NotificationPreferencesRoute(val accountId: String)

/** One artifact of an account, at [version] (the latest one when null), opened from the Artifacts tab. */
@Serializable
data class ArtifactRoute(val accountId: String, val artifactId: String, val version: Int? = null)

/** One session of an account: its record, its gate answers and its todo, opened from the Sessions tab or a run. */
@Serializable
data class SessionRoute(val accountId: String, val sessionId: String)

/** One workflow of an account, read only, with its runs, opened from the Workflows tab. */
@Serializable
data class WorkflowRoute(val accountId: String, val workflowId: String)

/** One run of a workflow with its visits, opened from a runs list, a session or a parent run. */
@Serializable
data class WorkflowRunRoute(val accountId: String, val runId: String)
