// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.TodoItemState
import run.ostrom.app.infrastructure.repositories.RemoteTodoRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every state a todo item can be in, and a todo status this version does not know. */
class RemoteTodoRepositoryMappingTest {
    private val server = ScriptedServer()
    private val repository = RemoteTodoRepository(server.calls)

    private fun item(id: String, status: String) =
        """{"id":"$id","name":"N-$id","description":"","doneWhen":{"text":"t","checks":[]},"origin":"plan","status":"$status"}"""

    @Test fun everyItemStateIsMappedAndAnUnknownTodoStatusReadsAsActive() = runTest {
        server.signedIn()
        val statuses = listOf("todo", "doing", "done", "drop_requested", "dropped", "from-the-future")
        server.json(
            """{"todo":{"id":"t1","sessionId":"s1","reference":"Plan","status":"from-the-future","items":[${statuses.joinToString(",") { item(it, it) }}],
               "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:20:00Z"}}""",
        )
        val todo = repository.todo("a1", "s1")!!
        assertEquals(
            listOf(TodoItemState.TODO, TodoItemState.DOING, TodoItemState.DONE, TodoItemState.DROP_REQUESTED, TodoItemState.DROPPED, TodoItemState.UNKNOWN),
            todo.items.map { it.state },
        )
        assertEquals("active", todo.status)
        assertEquals(emptyList(), todo.hookEvents)
    }

    @Test fun aKnownTodoStatusIsKept() = runTest {
        server.signedIn()
        server.json(
            """{"todo":{"id":"t1","sessionId":"s1","reference":"Plan","status":"active","items":[],"createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:20:00Z"}}""",
        )
        assertEquals("active", repository.todo("a1", "s1")!!.status)
    }
}
