// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionRepository (Apache-2.0), companion/.../domain/repositories/SessionRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession

/**
 * The person's cloud sessions on an account's server (`runners.cloud.v1`). Failures are
 * [run.ostrom.app.domain.models.AppException]s; a refusal of an action is a
 * [run.ostrom.app.domain.models.AppException.CloudSessionRefused] worded by its key. Pause, resume and stop are
 * accepted at once and carried out by the server: the answer is the session after the request.
 */
interface CloudSessionRepository {
    /** Newest first; [before] is the previous page's cursor. */
    suspend fun sessions(accountId: String, before: String?): CloudSessionPage

    suspend fun session(accountId: String, sessionId: String): CloudSession

    /**
     * Starts [session] under [idempotencyKey] (`Idempotency-Key`): the same key with the same form returns the session
     * the first call started instead of a second one.
     */
    suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String): CloudSession

    suspend fun pause(accountId: String, sessionId: String): CloudSession

    suspend fun resume(accountId: String, sessionId: String): CloudSession

    suspend fun stop(accountId: String, sessionId: String): CloudSession

    /** Chooses whose GitHub access the session uses (`runners.cloud.github.v1`); the answer is the session after the change. */
    suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession
}
