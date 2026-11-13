// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.infrastructure.repositories

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Instant
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.BrowserAttempt
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.core.api.models.Capabilities
import run.ostrom.core.api.models.SignUpPolicy
import run.ostrom.core.client.OstromClient
import run.ostrom.core.client.Pkce

/** [AuthRepository] over the typed client; every failure is translated (never a server message). */
class RemoteAuthRepository(
    private val clients: OstromClientProvider,
    private val calls: AccountCalls,
    private val random: SecureRandom,
    private val scope: CoroutineScope,
) : AuthRepository {
    override suspend fun discover(origin: String): ServerInfo = detached(origin) { client ->
        val methods = client.auth.signInMethods()
        ServerInfo(client.origin, methods.methods.toSet(), inviteOnly = methods.signUp == SignUpPolicy.INVITE)
    }

    override fun prepareBrowserSignIn(origin: String, redirectUri: String, state: String): BrowserAttempt {
        val pkce = Pkce.create(random::nextBytes)
        val client = clients.detached(origin)
        return try {
            BrowserAttempt(client.auth.githubStartUrl(redirectUri, state, pkce.challenge), pkce.verifier)
        } finally {
            client.close()
        }
    }

    override suspend fun exchangeCode(origin: String, code: String, codeVerifier: String, redirectUri: String): NewSession = detached(origin) { client ->
        val answer = client.auth.exchangeMobileCode(code, codeVerifier, redirectUri)
        NewSession(answer.token, answer.user.login, answer.user.name, answer.expiresAt?.let(::epochMillis), answer.unlockKey)
    }

    override suspend fun startDeviceLogin(server: ServerInfo): DeviceLogin = detached(server.origin) { client ->
        val start = client.auth.startDeviceLogin()
        val page = start.verificationUri
        if (!trustedSignInPage(page, server.origin) || page.any { it < ' ' || it == '\u007f' }) throw AppException.UntrustedSignInPage()
        if (start.deviceCode.isEmpty() || !USER_CODE.matches(start.userCode)) throw AppException.NotOstrom()
        DeviceLogin(
            server = server,
            userCode = start.userCode,
            verificationUri = page,
            deviceCode = start.deviceCode,
            // A server's interval of 0 would ask in a tight loop, and a huge expiry would never end (the CLI's bounds).
            intervalMillis = start.interval.toLong().coerceIn(1, MAX_INTERVAL_SECONDS) * MILLIS,
            expiresInMillis = start.expiresIn.toLong().coerceIn(1, MAX_EXPIRY_SECONDS) * MILLIS,
        )
    }

    override suspend fun pollDeviceLogin(origin: String, deviceCode: String): DevicePoll = try {
        detached(origin) { client ->
            val done = client.auth.pollDeviceLogin(deviceCode)
            if (done.token.isEmpty() || done.login.isEmpty()) throw AppException.NotOstrom()
            DevicePoll.Done(NewSession(done.token, done.login, null, done.expiresAt?.let(::epochMillis)))
        }
    } catch (error: AppException.Server) {
        when {
            error.statusCode == PENDING || error.code == "authorization_pending" -> DevicePoll.Pending()
            error.code == "slow_down" -> DevicePoll.Pending(slowDown = true)
            error.code == "expired_token" -> throw AppException.SignInExpired()
            error.code == "access_denied" -> throw AppException.SignInDenied()
            else -> throw error
        }
    }

    override suspend fun capabilities(origin: String, token: String): ServerCapabilities = tooOldIfMissing { detached(origin, token) { it.capabilities().toDomain() } }

    override suspend fun capabilities(accountId: String): ServerCapabilities = tooOldIfMissing { calls.withAccount(accountId) { it.capabilities().toDomain() } }

    /** A server without `GET /capabilities` predates API version 1: too old for this app, not a missing resource. */
    private inline fun tooOldIfMissing(read: () -> ServerCapabilities): ServerCapabilities = try {
        read()
    } catch (error: AppException) {
        throw if (error is AppException.NotFound || (error is AppException.Server && error.statusCode == NOT_FOUND)) AppException.UnsupportedApi(null) else error
    }

    override suspend fun organizations(origin: String, token: String): List<Organization> = detached(origin, token) { it.organizationsOf() }

    override suspend fun organizations(accountId: String): List<Organization> = calls.withAccount(accountId) { it.organizationsOf() }

    override suspend fun me(origin: String, token: String): Member = detached(origin, token) { it.member() }

    override suspend fun me(accountId: String): Member = calls.withAccount(accountId) { it.member() }

    override suspend fun logout(accountId: String) {
        calls.withAccount(accountId) { it.auth.logout() }
    }

    override suspend fun release(accountId: String) = clients.release(accountId)

    override fun abandon(origin: String, token: String) {
        scope.launch {
            try {
                detached(origin, token) { it.auth.logout() }
            } catch (_: Exception) {
                // Best effort: an unreachable server lets the session expire on its own.
            }
        }
    }

    private suspend fun OstromClient.organizationsOf(): List<Organization> = organizations.mine().map { Organization(it.id, it.name) }

    private suspend fun OstromClient.member(): Member {
        val me = auth.me()
        return Member(me.dev.login, me.organization?.let { Organization(it.id, it.name) })
    }

    private suspend fun <T> detached(origin: String, token: String? = null, call: suspend (OstromClient) -> T): T {
        val client = try {
            clients.detached(origin, token)
        } catch (_: IllegalArgumentException) {
            throw AppException.InvalidOrigin()
        }
        return try {
            translateFailures { call(client) }
        } finally {
            client.close()
        }
    }

    private fun Capabilities.toDomain() = ServerCapabilities(serverVersion, apiVersion, features.toSet())

    companion object {
        private const val GITHUB_WEB = "https://github.com"
        private const val PENDING = 428
        private const val NOT_FOUND = 404
        private const val MILLIS = 1_000L
        private const val MAX_INTERVAL_SECONDS = 60L
        private const val MAX_EXPIRY_SECONDS = 1_800L
        private val USER_CODE = Regex("[A-Za-z0-9-]{1,32}")
        private val ORIGIN = Regex("""(https?)://(\[[0-9a-f:.]+]|[^/?#@:\[\]]+)(?::(\d{1,5}))?(?:[/?#].*)?""", RegexOption.IGNORE_CASE)
        private val THIS_MACHINE = Regex("""http://(localhost|127\.0\.0\.1|\[::1]):\d+""")

        /**
         * Whether [page] is GitHub's, or of the very origin of the server or of its console (`app.` for `api.`), over
         * HTTPS (or `http://` to this machine): a server cannot send the person to a look-alike page to collect their
         * code (ostrom-cli's DeviceLogin, security audit S2-35).
         */
        internal fun trustedSignInPage(page: String, serverOrigin: String): Boolean {
            if (page.startsWith("$GITHUB_WEB/")) return true
            val origin = originOf(page) ?: return false
            val secure = origin.startsWith("https://") || THIS_MACHINE.matches(origin)
            val homes = listOf(serverOrigin, serverOrigin.replaceFirst("://api.", "://app."))
            return secure && homes.any { originOf(it) == origin }
        }

        private fun originOf(url: String): String? {
            val match = ORIGIN.matchEntire(url) ?: return null
            val (scheme, host, port) = match.destructured.toList().map { it.lowercase() }
            return "$scheme://$host:" + port.ifEmpty { if (scheme == "https") "443" else "80" }
        }

        /** An RFC 3339 instant in epoch milliseconds, or null when the server wrote something else. */
        internal fun epochMillis(value: String): Long? = try {
            Instant.parse(value).toEpochMilliseconds()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
