// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/CachedFeaturesRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.find

/** Capabilities per account, read once and again on refresh (each time the app comes back to the foreground). */
class CachedFeaturesRepository(private val auth: AuthRepository, private val accounts: AccountRepository) : FeaturesRepository {
    private val lock = Mutex()
    // Read without suspending ([cached], from a screen) and written after a call: a copy-on-write map, never a shared mutable one.
    @Volatile
    private var cache: Map<String, Pair<String, ServerCapabilities>> = emptyMap()

    override suspend fun capabilities(accountId: String, refresh: Boolean): ServerCapabilities {
        val origin = accounts.find(accountId)?.origin ?: throw AppException.Unauthorized()
        if (!refresh) lock.withLock { cache[accountId]?.takeIf { it.first == origin }?.let { return it.second } }
        val capabilities = auth.capabilities(accountId)
        lock.withLock { cache = cache + (accountId to (origin to capabilities)) }
        return capabilities
    }

    override fun cached(accountId: String): ServerCapabilities? {
        val origin = accounts.find(accountId)?.origin ?: return null
        return cache[accountId]?.takeIf { it.first == origin }?.second
    }
}
