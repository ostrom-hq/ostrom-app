// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.ServerInfo

/** A session the server just created, not stored yet. [unlockKey] comes with a phone's sign-in only. */
class NewSession(
    val token: String,
    val login: String,
    val name: String?,
    val expiresAt: Long?,
    val unlockKey: String? = null,
) {
    override fun toString(): String = "NewSession($login)"
}

/** What the browser is opened on, and the PKCE verifier only this attempt knows. */
class BrowserAttempt(val url: String, internal val codeVerifier: String) {
    override fun toString(): String = "BrowserAttempt($url)"
}

/** One answer of the device flow's polling. */
sealed interface DevicePoll {
    /** Not answered yet: ask again after the interval ([slowDown]: and add five seconds to it, as GitHub asks). */
    data class Pending(val slowDown: Boolean = false) : DevicePoll

    class Done(val session: NewSession) : DevicePoll
}

/** The person behind an account, read again with its token. */
data class Member(val login: String, val organization: Organization?)

/**
 * Authentication calls of an Ostrom server. Failures are [run.ostrom.app.domain.models.AppException]s. Calls with an
 * explicit token run before an account exists; the others use the token stored for the account.
 */
interface AuthRepository {
    /** Checks, without any credential, that [origin] answers like an Ostrom server (`GET /auth/providers`). */
    suspend fun discover(origin: String): ServerInfo

    /**
     * Draws a PKCE verifier and gives the address where the browser starts a GitHub sign-in that comes back to
     * [redirectUri] with [state] and a code for that verifier (\`GET /auth/github/start\`).
     */
    fun prepareBrowserSignIn(origin: String, redirectUri: String, state: String): BrowserAttempt

    /** Exchanges the browser's one-time code for an app token (`POST /auth/mobile/token`). */
    suspend fun exchangeCode(origin: String, code: String, codeVerifier: String, redirectUri: String): NewSession

    /** Starts the device flow of `ostrom login` (`POST /auth/device`). */
    suspend fun startDeviceLogin(server: ServerInfo): DeviceLogin

    /** Asks once whether the person entered the code (`POST /auth/device/token`). */
    suspend fun pollDeviceLogin(origin: String, deviceCode: String): DevicePoll

    /** `GET /capabilities` with a session that was just created and is not stored yet. */
    suspend fun capabilities(origin: String, token: String): ServerCapabilities

    /** `GET /capabilities` with the token stored for the account. */
    suspend fun capabilities(accountId: String): ServerCapabilities

    /** The organizations the person belongs to, with a new session or an account's token. */
    suspend fun organizations(origin: String, token: String): List<Organization>
    suspend fun organizations(accountId: String): List<Organization>

    /** `GET /auth/me` with a new session, or with the account's token. */
    suspend fun me(origin: String, token: String): Member
    suspend fun me(accountId: String): Member

    /** Revokes the account's session on the server. */
    suspend fun logout(accountId: String)

    /** Closes what was opened for the account (its HTTP client). */
    suspend fun release(accountId: String)

    /** Revokes a session that was never stored, best-effort and in the background. */
    fun abandon(origin: String, token: String)
}
