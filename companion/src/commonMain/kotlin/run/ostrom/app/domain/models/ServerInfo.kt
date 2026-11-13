// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.models

/**
 * A server found at [origin] before sign-in, shown with its origin so the person knows where they sign in.
 *
 * @property methods The sign-in methods the instance offers (`github`, `google`, `email`).
 * @property inviteOnly A sign-in creates an account only with an invitation: said before the browser opens.
 */
data class ServerInfo(
    val origin: String,
    val methods: Set<String> = setOf(GITHUB),
    val inviteOnly: Boolean = false,
) {
    val offersGitHub: Boolean get() = GITHUB in methods

    companion object {
        const val GITHUB = "github"
    }
}

/** What an authenticated `GET /api/v1/capabilities` announces. */
data class ServerCapabilities(
    val serverVersion: String,
    val apiVersion: Int,
    val features: Set<String>,
)
