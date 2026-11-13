// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/client/TeamCodeClientProvider.kt; see NOTICE.
package run.ostrom.app.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.core.client.OstromClient

/**
 * One [OstromClient] per account, created on first use and closed on sign-out. Its token provider reads the
 * [CredentialStore] on every call, so a token written or deleted there takes effect at once, and its organization
 * provider reads the account's choice (`Ostrom-Org`) the same way. Every HTTP client it creates is its own.
 */
class OstromClientProvider(
    private val engine: HttpClientEngineFactory<*>,
    private val credentials: CredentialStore,
    private val accounts: AccountRepository,
    private val platform: PlatformInfo,
) {
    private val lock = Mutex()
    private val clients = mutableMapOf<String, Pair<String, OstromClient>>()

    suspend fun forAccount(accountId: String, origin: String): OstromClient = lock.withLock {
        clients[accountId]?.takeIf { it.first == origin }?.second ?: create(origin, { readToken(accountId) }, { accounts.find(accountId)?.organizationId }).also {
            clients.remove(accountId)?.second?.close()
            clients[accountId] = origin to it
        }
    }

    /** A client for calls before an account exists (discovery, sign-in, the new session's first reads); close it after use. */
    fun detached(origin: String, token: String? = null): OstromClient = create(origin, { token }, { null })

    suspend fun release(accountId: String) {
        lock.withLock { clients.remove(accountId) }?.second?.close()
    }

    private fun create(origin: String, token: suspend () -> String?, organization: suspend () -> String?) =
        OstromClient(
            serverUrl = origin,
            token = token,
            organization = organization,
            createHttpClient = { configure -> HttpClient(engine) { configure(this) } },
            userAgent = "Ostrom-App/${platform.appVersion} (${platform.platform})",
        )

    /** A secure storage failure is reported as such, not mistaken for an unreachable server. */
    private suspend fun readToken(accountId: String): String? = try {
        credentials.read(accountId)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw AppException.CredentialStorage()
    }
}
