// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/SessionFileRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.FilePolicy
import run.ostrom.app.domain.models.PickedFile
import run.ostrom.app.domain.models.UploadedFile

/**
 * Files sent with messages (`runners.cloud.files.v1`). Failures are [run.ostrom.app.domain.models.AppException]s;
 * a refusal of the file itself (its size, its type, a quota, the team's storage) is
 * [run.ostrom.app.domain.models.AppException.FileRefused].
 */
interface SessionFileRepository {
    suspend fun policy(accountId: String): FilePolicy

    /**
     * Uploads [file] to [sessionId] (the server recognizes its type from the bytes), retrying network failures and
     * server errors itself under the same
     * [idempotencyKey], so the server never stores it twice; [onProgress] gets the bytes sent so far, from 0 at each attempt.
     */
    suspend fun upload(accountId: String, sessionId: String, file: PickedFile, idempotencyKey: String, onProgress: (sent: Long, total: Long) -> Unit): UploadedFile

    /** The MIME type [name] has by its extension, among the types files may have; null for any other extension. */
    fun typeFor(name: String): String?
}
