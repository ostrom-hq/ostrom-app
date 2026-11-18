// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.repositories.TodoRepository
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.todos.FollowTodoUseCase
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TodoEdgeCasesTest {
    private class Todos(var failure: Exception? = null) : TodoRepository {
        var reads = 0
        override suspend fun todo(accountId: String, sessionId: String): SessionTodo? { reads++; failure?.let { throw it }; return null }
    }

    private val features = FakeFeatures(setOf(Features.TODOS))

    @Test
    fun aSessionWithoutIdHasNoTodo() = runTest {
        val todos = Todos()
        assertTrue(FollowTodoUseCase(todos, OrgFeed(FakeEventRepository(), features, backgroundScope), features, fakeGuard())("a1", " ").toList().isEmpty())
        assertEquals(0, todos.reads)
    }

    @Test
    fun aTodoThatCannotBeReadKeepsWhatIsShown() = runTest {
        val todos = Todos(AppException.Unreachable())
        assertTrue(FollowTodoUseCase(todos, OrgFeed(FakeEventRepository(), features, backgroundScope), features, fakeGuard())("a1", "s1").toList().isEmpty())
        assertEquals(1, todos.reads)
    }

    @Test
    fun aRefusedSessionEndsTheTodo() = runTest {
        val todos = Todos(AppException.Unauthorized())
        assertFailsWith<AppException.Unauthorized> {
            FollowTodoUseCase(todos, OrgFeed(FakeEventRepository(), features, backgroundScope), features, fakeGuard())("a1", "s1").toList()
        }
    }
}
