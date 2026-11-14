// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteTodoRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.models.TodoHookEvent
import run.ostrom.app.domain.models.TodoItem
import run.ostrom.app.domain.models.TodoItemState
import run.ostrom.app.domain.models.TodoProof
import run.ostrom.app.domain.repositories.TodoRepository
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoItemOrigin
import run.ostrom.core.api.models.TodoItemStatus
import run.ostrom.core.api.models.TodoItem as ApiTodoItem

/** The session todo over the typed client (`GET /api/v1/todos/session?sessionId=`), one request per call. */
class RemoteTodoRepository(private val calls: AccountCalls) : TodoRepository {
    override suspend fun todo(accountId: String, sessionId: String): SessionTodo? =
        calls.withAccount(accountId) { it.todos.session(sessionId).todo?.domain() }
}

internal fun Todo.domain() = SessionTodo(
    sessionId = sessionId, reference = reference, status = status.value.ifEmpty { "active" },
    items = items.map { it.domain() }, updatedAt = millisOf(updatedAt), hookEvents = hookEvents.map { TodoHookEvent(millisOf(it.at), it.kind.value, it.text) },
)

internal fun ApiTodoItem.domain() = TodoItem(
    id = id, name = name, description = description, doneWhen = doneWhen.text,
    state = when (status) {
        TodoItemStatus.TODO -> TodoItemState.TODO
        TodoItemStatus.DOING -> TodoItemState.DOING
        TodoItemStatus.DONE -> TodoItemState.DONE
        TodoItemStatus.DROP_REQUESTED -> TodoItemState.DROP_REQUESTED
        TodoItemStatus.DROPPED -> TodoItemState.DROPPED
        TodoItemStatus.UNKNOWN -> TodoItemState.UNKNOWN
    },
    addedLater = origin == TodoItemOrigin.DISCOVERED, addedReason = originReason,
    proof = proof?.let { TodoProof(millisOf(it.at), it.passed, it.text, it.urls) }, unverified = unverified, dropReason = dropReason,
)
