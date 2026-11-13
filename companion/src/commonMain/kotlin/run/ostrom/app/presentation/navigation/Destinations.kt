// SPDX-License-Identifier: AGPL-3.0-only
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
