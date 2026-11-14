// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion infrastructure/repositories/RemotePushRepository.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.ostrom.app.domain.repositories.PushRepository

/**
 * The phone's registration over the typed client (`POST` and `DELETE /api/v1/push-devices`). The device id each
 * registration returned is kept in memory, to delete it before a sign-out: the app registers again at every start, and
 * the server forgets on its own the phones of a session it revokes.
 */
class RemotePushRepository(private val calls: AccountCalls) : PushRepository {
    private val lock = Mutex()
    private val devices = mutableMapOf<String, String>()

    override suspend fun register(accountId: String, token: String, publicKey: String, platform: String, locale: String?) {
        val device = calls.withAccount(accountId) { it.pushDevices.register(token, FCM, platform, locale, publicKey) }
        lock.withLock { devices[accountId] = device.id }
    }

    override suspend fun clear(accountId: String) {
        val id = lock.withLock { devices.remove(accountId) } ?: return
        calls.withAccount(accountId) { it.pushDevices.delete(id) }
    }

    private companion object {
        /** iOS and Android alike: FCM hands an iOS push to APNs. */
        const val FCM = "fcm"
    }
}
