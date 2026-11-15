// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/sessions/DeviceSessionUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.sessions

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.DeviceFolder
import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.HostDevice
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.ResumableSessions
import run.ostrom.app.domain.repositories.DeviceRepository
import run.ostrom.app.domain.repositories.PreferencesRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

/**
 * The devices a session can be started on: the member's own, and only those whose Ostrom daemon is online now (an
 * offline device is never offered, the server would refuse it). This device first, then by name.
 */
class LoadHostDevicesUseCase(private val devices: DeviceRepository, private val access: CloudSessionAccess) {
    suspend operator fun invoke(accountId: String): List<HostDevice> = access.call(accountId) { announced ->
        if (Features.RUNNERS_CLOUD_REMOTE_DAEMON !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_REMOTE_DAEMON)
        devices.onlineDevices(accountId).sortedWith(compareBy<HostDevice> { !it.current }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }
}

/**
 * The folders a device offers, by how its owner recognizes them. Never a path typed here: the daemon publishes the
 * folders its owner added (`ostrom folders add`). A device whose daemon went offline meanwhile is refused.
 */
class LoadDeviceFoldersUseCase(private val devices: DeviceRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, deviceId: String): List<DeviceFolder> = detailed(accountId, deviceId).folders

    /** The same folders, with whether the device's daemon lists their conversations to continue. */
    suspend fun detailed(accountId: String, deviceId: String): DeviceFolders = guard(accountId) {
        val found = deviceRefusals { devices.folders(accountId, deviceId) }
        if (!found.online) throw AppException.DeviceRefused(AppException.DeviceRefused.DEVICE_OFFLINE)
        found.copy(folders = found.folders.sortedWith(compareBy<DeviceFolder> { (it.label ?: it.name).lowercase() }.thenBy { it.id }))
    }
}

/**
 * The Claude Code conversations of a device's folder that a new session can continue, the most recent first, read by
 * the device's daemon when asked (`runners.cloud.remote.resume.v1`). Only ids, short titles, dates and message counts
 * come back; the server keeps none of them. Refusals about the device or the folder are [AppException.DeviceRefused].
 */
class LoadResumableSessionsUseCase(private val devices: DeviceRepository, private val access: CloudSessionAccess) {
    suspend operator fun invoke(accountId: String, deviceId: String, folderId: String, before: String? = null): ResumableSessions = access.call(accountId) { announced ->
        if (Features.RUNNERS_CLOUD_REMOTE_RESUME !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_REMOTE_RESUME)
        deviceRefusals { devices.resumable(accountId, deviceId, folderId, PAGE, before) }
    }

    companion object {
        const val PAGE = 20
    }
}

/**
 * Starts a session on one of the member's devices, in one of its folders, with the permission mode chosen now, which is
 * kept for the next launch once the server created the session. The caller keeps [idempotencyKey] for the form, as for
 * a cloud session. The server's refusals about the device (offline, folder no longer offered, ...) are
 * [AppException.DeviceRefused].
 */
class CreateDeviceSessionUseCase(private val access: CloudSessionAccess, private val preferences: PreferencesRepository) {
    suspend operator fun invoke(accountId: String, form: NewDeviceSession, idempotencyKey: String): CloudSession {
        val sent = form.normalized()
        if (sent.deviceId.isBlank()) throw AppException.CloudSessionInvalid(CloudSessionProblem.CHOOSE_DEVICE)
        if (sent.folderId.isBlank()) throw AppException.CloudSessionInvalid(CloudSessionProblem.CHOOSE_FOLDER)
        if (sent.agent !in AGENTS) throw AppException.CloudSessionInvalid(CloudSessionProblem.CHOOSE_AGENT)
        if (sent.name.isEmpty() || sent.name.length > CloudSessionRules.MAX_NAME || sent.name.any(Char::isISOControl)) {
            throw AppException.CloudSessionInvalid(CloudSessionProblem.NAME)
        }
        // The server's rules for a model and an effort, as when a session's are changed.
        if (sent.model != null && !ControlConversationUseCase.validModel(sent.model)) throw AppException.CloudSessionInvalid(CloudSessionProblem.MODEL)
        if (sent.effort != null && !ControlConversationUseCase.validEffort(sent.effort)) throw AppException.CloudSessionInvalid(CloudSessionProblem.EFFORT)
        val message = sent.initialMessage
        if (message != null && message.length > CloudSessionRules.MAX_PROMPT) throw AppException.CloudSessionInvalid(CloudSessionProblem.PROMPT)
        return access.call(accountId) { announced ->
            if (Features.RUNNERS_CLOUD_REMOTE_DAEMON !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_REMOTE_DAEMON)
            if (sent.resumeSessionId != null && Features.RUNNERS_CLOUD_REMOTE_RESUME !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_REMOTE_RESUME)
            val session = deviceRefusals { access.repository.createOnDevice(accountId, sent, idempotencyKey) }
            // Kept once a session started with it: a refused launch leaves the previous choice.
            preferences.setPermissionMode(sent.permissionMode)
            session
        }
    }

    companion object {
        /** The agents a device's daemon runs. */
        val AGENTS = setOf("claude", "codex")
    }
}

/** The server's refusals about a device, worded; any other failure stays as it is. */
private inline fun <T> deviceRefusals(call: () -> T): T = try {
    call()
} catch (error: AppException.Server) {
    val code = error.code
    if (code != null && code in AppException.DeviceRefused.CODES) throw AppException.DeviceRefused(code)
    throw error
}
