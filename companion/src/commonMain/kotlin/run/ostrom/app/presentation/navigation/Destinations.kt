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
 * On a phone, a tab its bar has no room for ([tab], a [run.ostrom.app.presentation.screens.MainTab] name), opened from
 * Settings above the tabs; [opensRequests]: its server serves `gates.v1`, so an activity line opens its request.
 */
@Serializable
data class MoreTabRoute(val tab: String, val opensRequests: Boolean)

/**
 * One request for a yes of [accountId]'s server: opened from the inbox, and by id from a push or a link. The request is
 * read again from the server each time; an answer goes only from this screen.
 */
@Serializable
data class RequestRoute(val accountId: String, val id: String)

/** The notification preferences of an account (`notifications.v1`), opened from Settings. */
@Serializable
data class NotificationPreferencesRoute(val accountId: String)

/** The member's environment variables of an account (`environment.v1`), names only, opened from Settings. */
@Serializable
data class MemberEnvironmentRoute(val accountId: String)

/** The member's personal MCP servers of an account (`mcp.personal.v1`), opened from Settings. */
@Serializable
data class PersonalMcpServersRoute(val accountId: String)

/** The member's GitHub link on an account's server (`github.link.v1`): which account is linked, and linking one, opened from Settings. */
@Serializable
data class GitHubLinkRoute(val accountId: String)

/** One artifact of an account, at [version] (the latest one when null), opened from the Artifacts tab. */
@Serializable
data class ArtifactRoute(val accountId: String, val artifactId: String, val version: Int? = null)

/** One session of an account: its record, its gate answers and its todo, opened from the Sessions tab or a run. */
@Serializable
data class SessionRoute(val accountId: String, val sessionId: String)

/** One signed receipt of an account by its id, verified on this device as the History tab verifies it, opened from a session's record. */
@Serializable
data class ReceiptRoute(val accountId: String, val receiptId: String)

/** One workflow of an account, read only, with its runs, opened from the Workflows tab. */
@Serializable
data class WorkflowRoute(val accountId: String, val workflowId: String)

/** The form that starts a run of a workflow, opened from the workflow's Start. */
@Serializable
data class WorkflowStartRoute(val accountId: String, val workflowId: String)

/** One run of a workflow with its visits, opened from a runs list, a session or a parent run. */
@Serializable
data class WorkflowRunRoute(val accountId: String, val runId: String)

/** The workflow editor: a saved workflow ([workflowId]) or a new one (null), opened from a workflow's Edit or the list's New. */
@Serializable
data class WorkflowEditRoute(val accountId: String, val workflowId: String? = null)

/** One of the person's cloud sessions of an account (`runners.cloud.v1`), with pause, resume and stop, opened from the Sessions tab. */
@Serializable
data class CloudSessionRoute(val accountId: String, val sessionId: String)

/** The transfer sheet's session (a sheet over the cloud session, not a destination of its own). */
@Serializable
data class TransferRoute(val accountId: String, val sessionId: String, val ownerId: String)

/** An account's incoming and outgoing session transfers (`runners.cloud.transfer.v1`), opened from the cloud sessions list. */
@Serializable
data class TransfersRoute(val accountId: String)

/** The form that starts a cloud session, opened from the cloud sessions list. */
@Serializable
data class NewCloudSessionRoute(val accountId: String)
