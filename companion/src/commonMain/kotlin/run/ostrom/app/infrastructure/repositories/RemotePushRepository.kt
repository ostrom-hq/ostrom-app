// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion infrastructure/repositories/RemotePushRepository.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import run.ostrom.app.domain.repositories.PushRepository

/**
 * The phone's registration over the typed client (`POST` and `DELETE /api/v1/push-devices`). The device id each
 * registration returned is kept in memory with the organization it was made in, to delete it before a sign-out and
 * after a change of organization (a registration belongs to one organization, so the old one would keep receiving
 * its pushes): the app registers again at every start, and the server forgets on its own the phones of a session it
 * revokes.
 */
class RemotePushRepository(private val calls: AccountCalls) : PushRepository {
    private data class Device(val id: String, val organizationId: String?)

    private val lock = Mutex()
    private val devices = mutableMapOf<String, Device>()

    override suspend fun register(accountId: String, token: String, publicKey: String, platform: String, locale: String?) {
        // Read once: the registration and what is kept of it name the same organization, even if it changes meanwhile.
        val organizationId = calls.organizationOf(accountId)
        val device = calls.withAccountIn(accountId, organizationId) { it.pushDevices.register(token, FCM, platform, locale, publicKey) }
        val previous = lock.withLock { devices.put(accountId, Device(device.id, organizationId)) }
        if (previous != null && previous.organizationId != organizationId) forget(accountId, previous)
    }

    override suspend fun clear(accountId: String) {
        val device = lock.withLock { devices.remove(accountId) } ?: return
        calls.withAccountIn(accountId, device.organizationId) { it.pushDevices.delete(device.id) }
    }

    /**
     * Best effort (a short call, bounded by `translateFailures`), and not cancelled with the sync that asked (a newer
     * change of accounts): once the new registration replaced it here, nothing else knows the old device's id.
     */
    private suspend fun forget(accountId: String, device: Device) = withContext(NonCancellable) {
        try {
            calls.withAccountIn(accountId, device.organizationId) { it.pushDevices.delete(device.id) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Offline or refused: the new registration stands, and the old organization's server keeps the phone
            // until it revokes the session (the ids are kept in memory only, not across a restart).
        }
    }

    private companion object {
        /** iOS and Android alike: FCM hands an iOS push to APNs. */
        const val FCM = "fcm"
    }
}
