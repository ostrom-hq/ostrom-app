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
