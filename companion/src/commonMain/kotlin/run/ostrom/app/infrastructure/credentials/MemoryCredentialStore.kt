// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/credentials/MemoryCredentialStore.kt; see NOTICE.
package run.ostrom.app.infrastructure.credentials

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.ostrom.app.domain.services.CredentialStore

/** Tokens for this run of the app only: the fallback when the device has no usable secure store. */
class MemoryCredentialStore : CredentialStore {
    private val lock = Mutex()
    private val tokens = mutableMapOf<String, String>()
    override val persistent = false
    override suspend fun read(accountId: String): String? = lock.withLock { tokens[accountId] }
    override suspend fun write(accountId: String, token: String) = lock.withLock { tokens[accountId] = token }
    override suspend fun delete(accountId: String) = lock.withLock { tokens.remove(accountId); Unit }
}
