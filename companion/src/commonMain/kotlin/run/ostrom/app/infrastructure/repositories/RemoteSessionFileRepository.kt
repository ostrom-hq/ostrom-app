// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteSessionFileRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.FilePolicy
import run.ostrom.app.domain.models.PickedFile
import run.ostrom.app.domain.models.UploadedFile
import run.ostrom.app.domain.repositories.SessionFileRepository
import run.ostrom.app.domain.usecases.files.SessionFilesUseCase
import run.ostrom.core.client.CloudFileReadException
import run.ostrom.core.client.CloudFileSource
import run.ostrom.core.client.CloudFileTypes
import run.ostrom.core.client.CloudFilesClient
import run.ostrom.core.client.OstromException

/**
 * Files over the typed client: `cloudFiles.policy()` and `cloudFiles.upload`, which streams the file, retries transient
 * failures under one idempotency key and checks the stored SHA-256. A refusal of the file is kept with its code
 * ([AppException.FileRefused]); a `401` and a missing session are translated as for any call.
 */
class RemoteSessionFileRepository(private val calls: AccountCalls) : SessionFileRepository {
    override suspend fun policy(accountId: String): FilePolicy = calls.withAccount(accountId) { client ->
        val policy = client.cloudFiles.policy()
        FilePolicy(policy.available, policy.reason, policy.maxFileBytes, policy.maxMessageBytes, policy.maxFilesPerMessage, policy.types)
    }

    override suspend fun upload(accountId: String, sessionId: String, file: PickedFile, idempotencyKey: String,
        onProgress: (sent: Long, total: Long) -> Unit): UploadedFile = calls.withAccountLong(accountId, UPLOAD_TIMEOUT_MILLIS) { client ->
        val uploaded = try {
            client.cloudFiles.upload(sessionId, file.name, CloudFileSource(file.size, file.open), idempotencyKey, onProgress = onProgress)
        } catch (_: CloudFileReadException) {
            throw AppException.FileRefused(SessionFilesUseCase.FILE_UNREADABLE, 0)
        } catch (error: OstromException) {
            throw refusal(error) ?: error
        }
        UploadedFile(uploaded.id, uploaded.name, uploaded.size, uploaded.contentType)
    }

    override fun typeFor(name: String): String? = CloudFileTypes.forName(name)

    /** The refusal of the file itself; null for what any call translates (signed out, the session gone, rate limits). */
    private fun refusal(error: OstromException): AppException.FileRefused? = when {
        error.type == CloudFilesClient.FILE_INTEGRITY_FAILED -> AppException.FileRefused(error.type, 0)
        // An upload's only status 0 is the integrity check above; a dropped connection is no OstromException.
        error.status == 401 || error.status == 404 -> null
        // An organization still to choose, or a fresh proof to give: translated as for any call.
        error.status == 409 && error.type == ORG_REQUIRED || error.status == 403 && error.type == STEP_UP_REQUIRED -> null
        else -> AppException.FileRefused(error.type, error.status, error.retryAfterSeconds)
    }

    private companion object {
        /** Every attempt of one upload together; the client bounds each attempt to 15 minutes. */
        const val UPLOAD_TIMEOUT_MILLIS = 30 * 60_000L
    }
}
