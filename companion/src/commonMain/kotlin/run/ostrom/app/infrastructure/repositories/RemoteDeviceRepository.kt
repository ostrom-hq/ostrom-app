// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteDeviceRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.DeviceFolder
import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.domain.models.HostDevice
import run.ostrom.app.domain.models.ResumableSession
import run.ostrom.app.domain.models.ResumableSessions
import run.ostrom.app.domain.repositories.DeviceRepository
import run.ostrom.core.api.models.DaemonFeatures

/** The member's devices over the typed client (`/api/v1/devices`, `runners.cloud.remote.daemon.v1`). */
class RemoteDeviceRepository(private val calls: AccountCalls) : DeviceRepository {
    override suspend fun onlineDevices(accountId: String): List<HostDevice> = calls.withAccount(accountId) { client ->
        // `GET /devices` lists the caller's own devices only: another member's computer is never offered.
        client.devices.list().items.filter { it.daemon?.online == true }.map { HostDevice(it.id, it.name, it.platform, it.osVersion, it.current == true) }
    }

    override suspend fun folders(accountId: String, deviceId: String): DeviceFolders = calls.withAccount(accountId) { client ->
        val view = client.deviceDaemon.folders(deviceId)
        DeviceFolders(view.daemon?.online == true, view.folders.map { DeviceFolder(it.id, it.name, it.label) }, DaemonFeatures.RESUME in view.daemon?.features.orEmpty())
    }

    override suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: String?): ResumableSessions =
        calls.withAccount(accountId) { client ->
            val page = client.deviceDaemon.resumable(deviceId, folderId, limit, before)
            ResumableSessions(page.items.map { ResumableSession(it.id, it.title, millisOf(it.lastActiveAt) ?: 0, it.turns) }, page.next)
        }
}
