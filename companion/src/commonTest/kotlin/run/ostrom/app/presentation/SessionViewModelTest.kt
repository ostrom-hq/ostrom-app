// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.SessionPage
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.repositories.SessionRepository
import run.ostrom.app.domain.repositories.TodoRepository
import run.ostrom.app.domain.session
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.sessions.GetSessionUseCase
import run.ostrom.app.domain.usecases.todos.FollowTodoUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.navigation.SessionRoute
import run.ostrom.app.presentation.viewmodels.SessionViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val features = FakeFeatures(setOf(Features.SESSIONS, Features.TODOS, Features.EVENTS))
    private val events = FakeEventRepository()
    private var failure: Exception? = null
    private val todoReads = mutableListOf<String>()

    private val sessions = object : SessionRepository {
        override suspend fun sessions(accountId: String, open: Boolean?, before: String?) = SessionPage(emptyList(), null)
        override suspend fun session(accountId: String, sessionId: String): AgentSession {
            failure?.let { throw it }
            return run.ostrom.app.domain.session(sessionId)
        }
    }

    private val todos = object : TodoRepository {
        override suspend fun todo(accountId: String, sessionId: String): SessionTodo? { todoReads += sessionId; return null }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(): SessionViewModel {
        val feed = OrgFeed(events, features, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        return SessionViewModel(SessionRoute("a1", "s1"), GetSessionUseCase(sessions, features, fakeGuard()), FollowTodoUseCase(todos, feed, features, fakeGuard()))
    }

    @Test
    fun aGoneSessionAndAFailedReadAreToldApart() = runTest {
        failure = AppException.NotFound()
        val model = model()
        assertTrue(model.uiState.value.gone)
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        failure = AppException.Unreachable()
        model.load()
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
        failure = null
        model.load()
        assertNull(model.uiState.value.error)
        assertEquals("s1", model.uiState.value.details?.session?.id)
    }

    @Test
    fun theTodoFollowsTheFeedWhileShownAndTheRecordIsReadAgainOnReturn() = runTest {
        val updates = Channel<LiveUpdate<OrgChange>>(Channel.UNLIMITED)
        events.changes = updates.receiveAsFlow()
        val model = model()
        // Stopping what never started is harmless.
        model.stop()
        model.start()
        updates.send(LiveUpdate.Opened(resumed = false))
        assertTrue(model.uiState.value.todoLoaded)
        assertEquals(listOf("s1"), todoReads)
        // The connection drops: the todo shown stays, with its reconnecting mark.
        updates.send(LiveUpdate.Reconnecting(1_000))
        assertTrue(model.uiState.value.todoReconnecting)
        assertTrue(model.uiState.value.todoLoaded)
        updates.send(LiveUpdate.Opened(resumed = true))
        assertFalse(model.uiState.value.todoReconnecting)
        // Back on the screen: the record is read again and the todo followed again.
        model.stop()
        assertFalse(model.uiState.value.todoReconnecting)
        model.start()
        model.start()
        assertNotNull(model.uiState.value.details)
        // The feed fails: what is shown stays.
        updates.close(AppException.Unreachable())
        assertFalse(model.uiState.value.todoReconnecting)
        assertTrue(model.uiState.value.todoLoaded)
    }
}
