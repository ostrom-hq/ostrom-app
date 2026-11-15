// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionRepository (Apache-2.0), companion/.../domain/repositories/SessionRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.SessionKind
import run.ostrom.app.domain.models.PermissionAnswer

/**
 * The person's cloud sessions on an account's server (`runners.cloud.v1`). Failures are
 * [run.ostrom.app.domain.models.AppException]s; a refusal of an action is a
 * [run.ostrom.app.domain.models.AppException.CloudSessionRefused] worded by its key. Pause, resume and stop are
 * accepted at once and carried out by the server: the answer is the session after the request.
 */
interface CloudSessionRepository {
    /**
     * Newest first; [before] is the previous page's cursor. [kinds] are asked of a server with remote sessions
     * (`runners.cloud.remote.v1`); empty, the server's default (its cloud sessions).
     */
    suspend fun sessions(accountId: String, before: String?, kinds: Set<SessionKind> = emptySet()): CloudSessionPage

    suspend fun session(accountId: String, sessionId: String): CloudSession

    /**
     * Starts [session] under [idempotencyKey] (`Idempotency-Key`): the same key with the same form returns the session
     * the first call started instead of a second one.
     */
    suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String): CloudSession

    /**
     * Starts [session] on one of the member's devices (`kind: remote`, `runners.cloud.remote.daemon.v1`) under
     * [idempotencyKey], as [create] does.
     */
    suspend fun createOnDevice(accountId: String, session: NewDeviceSession, idempotencyKey: String): CloudSession

    suspend fun pause(accountId: String, sessionId: String): CloudSession

    suspend fun resume(accountId: String, sessionId: String): CloudSession

    suspend fun stop(accountId: String, sessionId: String): CloudSession

    /** Chooses whose GitHub access the session uses (`runners.cloud.github.v1`); the answer is the session after the change. */
    suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession

    // A conversation session (`runners.cloud.conversation.v1` and the keys after it), ported from TeamCode's
    // SessionRepository: its inputs are acknowledged at once and carried out by the agent.

    /** Sends a message to the agent under [clientMessageId] (`Idempotency-Key`): a retry returns the first acknowledgement. */
    suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String> = emptyList()): InputReceipt

    /** Stops the agent's current turn; `409 nothing_to_interrupt` when no agent runs. */
    suspend fun interrupt(accountId: String, sessionId: String): InputReceipt

    /** Answers a permission request under [idempotencyKey] (`runners.cloud.permissions.v1`). */
    suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt

    /** Stops the agent's background task [taskId] (`runners.cloud.tasks.v1`). */
    suspend fun stopTask(accountId: String, sessionId: String, taskId: String): InputReceipt

    /** Turns the owner's standing approval of tool uses on or off (`runners.cloud.permissions.bypass.v1`). */
    suspend fun setPermissionBypass(accountId: String, sessionId: String, enabled: Boolean): CloudSession

    /** Switches the agent's model (`runners.cloud.model.v1`). */
    suspend fun setModel(accountId: String, sessionId: String, model: String): CloudSession

    /** Switches the agent's effort (`runners.cloud.effort.v1`). */
    suspend fun setEffort(accountId: String, sessionId: String, effort: String): CloudSession
}
