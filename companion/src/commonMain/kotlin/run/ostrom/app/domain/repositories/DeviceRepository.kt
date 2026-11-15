// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/DeviceRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.domain.models.HostDevice
import run.ostrom.app.domain.models.ResumableSessions

/** The member's own devices that can host a session (`runners.cloud.remote.daemon.v1`). */
interface DeviceRepository {
    /** The member's own devices whose daemon is online now; the others are never offered. */
    suspend fun onlineDevices(accountId: String): List<HostDevice>

    /** The folders [deviceId] published, owner only; a device of another member is [run.ostrom.app.domain.models.AppException.DeviceRefused]. */
    suspend fun folders(accountId: String, deviceId: String): DeviceFolders

    /** The conversations of [folderId] on [deviceId] a session can continue, read by its daemon now (`runners.cloud.remote.resume.v1`). */
    suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: String?): ResumableSessions
}
