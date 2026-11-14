// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/TodoRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.SessionTodo

/** The todo of a session (`todos.v1`). */
interface TodoRepository {
    /** The session's todo; null while its agent has none. */
    suspend fun todo(accountId: String, sessionId: String): SessionTodo?
}
