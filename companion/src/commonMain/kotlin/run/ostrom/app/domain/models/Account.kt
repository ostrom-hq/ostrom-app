// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion Account (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.models

import kotlinx.serialization.Serializable

/**
 * A person signed in on one Ostrom server. Only non-secret metadata: the token (and, for a phone, the unlock key) are in
 * the platform's credential store, keyed by [id]. The same server with two GitHub logins gives two accounts.
 *
 * @property login The person's login on the server (their GitHub login).
 * @property organizationId The organization the calls act in (`Ostrom-Org`); null until the person chose one, when they
 *   belong to several.
 * @property expiresAt When the token expires (epoch milliseconds), null when the server did not say.
 * @property method How the account signed in: with the browser on a phone (an app token and its unlock key), or with
 *   the device flow on a computer (the CLI's kind of token).
 */
@Serializable
data class Account(
    val id: String,
    val origin: String,
    val login: String,
    val name: String? = null,
    val organizationId: String? = null,
    val organizationName: String? = null,
    val expiresAt: Long? = null,
    val serverVersion: String = "",
    val method: SignInMethod = SignInMethod.BROWSER,
    /** False once the server refused the token or it expired: the account stays listed and asks to sign in again. */
    val signedIn: Boolean = true,
) {
    /** What the account switcher and the settings show. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: login

    /** The desktop's account of the CLI's configuration: its token is the CLI's. */
    val usedByCli: Boolean get() = id == CLI_ACCOUNT_ID

    companion object {
        /** The id of the CLI's account on the desktop; drawn ids are UUIDs, so it never collides with one. */
        const val CLI_ACCOUNT_ID = "cli"
    }
}

/** How an account signed in. */
@Serializable
enum class SignInMethod {
    /** GitHub in the system browser, back to `ostrom://auth/callback` (PKCE): a phone's app token and unlock key. */
    BROWSER,

    /** The device flow of `ostrom login`: a code to enter in the browser, for a computer. */
    DEVICE,
}

/** One organization the person belongs to. */
data class Organization(val id: String, val name: String)
