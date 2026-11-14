// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionRepository (Apache-2.0), companion/.../domain/repositories/SessionRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.SessionPage

/** The agent sessions of an account's server (`sessions.v1`). Failures are [run.ostrom.app.domain.models.AppException]s. */
interface SessionRepository {
    /** Newest first; [open] true for the open ones, false for the ended ones, null for all; [before] is the previous page's cursor. */
    suspend fun sessions(accountId: String, open: Boolean?, before: String?): SessionPage

    /** One session, with its gate answers. */
    suspend fun session(accountId: String, sessionId: String): AgentSession
}
