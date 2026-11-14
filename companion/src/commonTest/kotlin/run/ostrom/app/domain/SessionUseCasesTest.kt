// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion SessionUseCasesTest and TodoUseCasesTest (Apache-2.0); see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.SessionFilter
import run.ostrom.app.domain.models.SessionPage
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.models.TodoItem
import run.ostrom.app.domain.models.TodoItemState
import run.ostrom.app.domain.repositories.SessionRepository
import run.ostrom.app.domain.repositories.TodoRepository
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.sessions.FollowSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.GetSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadSessionsUseCase
import run.ostrom.app.domain.usecases.todos.FollowTodoUseCase
import run.ostrom.app.domain.usecases.todos.TodoUpdate
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun session(id: String = "s1", endedAt: Long? = null, lost: Boolean = false) = AgentSession(
    id = id, repo = "acme/api", agent = "claude-code", model = "claude-opus", dev = "ada", startedAt = 1_000L, endedAt = endedAt, endedBy = null,
    lastSeenAt = null, tools = 12, costUsd = 1.5, exitCode = null, segment = 1, receiptId = null, runId = null, lost = lost, activity = null,
)

private fun item(id: String, state: TodoItemState, unverified: Boolean = false) =
    TodoItem(id, "Item $id", "", "it works", state, addedLater = false, addedReason = null, proof = null, unverified = unverified, dropReason = null)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionUseCasesTest {
    private class FakeSessions : SessionRepository {
        val calls = mutableListOf<String>()
        override suspend fun sessions(accountId: String, open: Boolean?, before: String?): SessionPage {
            calls += "list $open ${before.orEmpty()}".trim()
            return SessionPage(listOf(session()), "c2")
        }
        override suspend fun session(accountId: String, sessionId: String): AgentSession { calls += "get $sessionId"; return session(sessionId) }
    }

    private class FakeTodos : TodoRepository {
        val calls = mutableListOf<String>()
        var todo: SessionTodo? = null
        var failure: Exception? = null
        override suspend fun todo(accountId: String, sessionId: String): SessionTodo? {
            calls += sessionId
            failure?.let { throw it }
            return todo
        }
    }

    @Test fun theListIsReadInItsFilterAndRefusedByAServerWithoutSessions() = runTest {
        val sessions = FakeSessions(); val features = FakeFeatures()
        val load = LoadSessionsUseCase(sessions, features, fakeGuard())
        assertFailsWith<AppException.Unsupported> { load("a1", SessionFilter.ALL) }
        features.features = setOf(Features.SESSIONS)
        load("a1", SessionFilter.ALL)
        load("a1", SessionFilter.OPEN, before = "c2")
        load("a1", SessionFilter.ENDED)
        assertEquals(listOf("list null", "list true c2", "list false"), sessions.calls)
    }

    @Test fun aRecordSaysWhatItCanOpenOnThisServer() = runTest {
        val sessions = FakeSessions(); val features = FakeFeatures(setOf(Features.SESSIONS))
        val get = GetSessionUseCase(sessions, features, fakeGuard())
        val bare = get("a1", "s1")
        assertFalse(bare.historyServed); assertFalse(bare.runsServed); assertFalse(bare.todosServed)
        features.features = setOf(Features.SESSIONS, Features.RECEIPTS, Features.WORKFLOWS, Features.TODOS)
        val full = get("a1", "s1")
        assertTrue(full.historyServed); assertTrue(full.runsServed); assertTrue(full.todosServed)
        assertFailsWith<AppException.NotFound> { get("a1", " ") }
        features.features = emptySet()
        assertFailsWith<AppException.Unsupported> { get("a1", "s1") }
        assertEquals(listOf("get s1", "get s1"), sessions.calls)
    }

    @Test fun anOpenSessionIsNeitherEndedNorLost() {
        assertTrue(session().open)
        assertFalse(session(endedAt = 2_000L).open)
        assertFalse(session(lost = true).open)
    }

    @Test fun theListLoadsWhenTheFeedOpensAndAfterAGap() = runTest {
        val features = FakeFeatures(setOf(Features.SESSIONS, Features.EVENTS, Features.WORKFLOWS))
        val events = FakeEventRepository(flowOf(
            LiveUpdate.Opened(false), LiveUpdate.Item(OrgChange(OrgChange.RUN_UPDATED, "r1", "m1", "wf1")), LiveUpdate.Reconnecting(1_000),
            LiveUpdate.Opened(true), LiveUpdate.Gap,
        ))
        val signals = FollowSessionsUseCase(OrgFeed(events, features, backgroundScope), features, fakeGuard())("a1").toList()
        assertEquals(
            listOf(FeedSignal.Connection(false), FeedSignal.Reload, FeedSignal.Connection(true), FeedSignal.Connection(false), FeedSignal.Reload),
            signals, "No session event exists: a load at the opening and after a gap only",
        )
    }

    @Test fun withoutTheFeedTheListLoadsOnceAndWithoutSessionsNever() = runTest {
        val events = FakeEventRepository()
        val features = FakeFeatures(setOf(Features.SESSIONS))
        val feed = OrgFeed(events, features, backgroundScope)
        assertEquals(listOf<FeedSignal>(FeedSignal.Reload), FollowSessionsUseCase(feed, features, fakeGuard())("a1").toList())
        features.features = setOf(Features.EVENTS)
        assertEquals(emptyList(), FollowSessionsUseCase(feed, features, fakeGuard())("a1").toList())
        assertEquals(emptyList(), events.asked)
    }

    @Test fun theTodoIsReadAtTheOpeningAfterAGapAndAtEachTodoEventOfItsSession() = runTest {
        val todos = FakeTodos().apply { todo = SessionTodo("s1", "The plan", "active", listOf(item("a", TodoItemState.DONE)), 1L) }
        val features = FakeFeatures(setOf(Features.TODOS, Features.EVENTS))
        val wire = Channel<LiveUpdate<OrgChange>>(Channel.UNLIMITED)
        val events = FakeEventRepository(flow { for (update in wire) emit(update) })
        val seen = mutableListOf<TodoUpdate>()
        val job = launch { FollowTodoUseCase(todos, OrgFeed(events, features, backgroundScope), features, fakeGuard())("a1", "s1").collect { seen += it } }
        runCurrent()
        wire.send(LiveUpdate.Opened(false)); runCurrent()
        wire.send(LiveUpdate.Item(OrgChange(OrgChange.TODO_UPDATED, "s2", "m1"))); runCurrent()
        wire.send(LiveUpdate.Item(OrgChange(OrgChange.TODO_UPDATED, "s1", "m1"))); runCurrent()
        wire.send(LiveUpdate.Item(OrgChange(OrgChange.WAIT_CREATED, "s1", "m1"))); runCurrent()
        wire.send(LiveUpdate.Reconnecting(1_000)); runCurrent()
        wire.send(LiveUpdate.Gap); runCurrent()
        // Offline: what is shown stays.
        todos.failure = AppException.Unreachable()
        wire.send(LiveUpdate.Item(OrgChange(OrgChange.TODO_UPDATED, "s1", "m1"))); runCurrent()
        assertEquals(listOf("s1", "s1", "s1", "s1"), todos.calls, "Read at the opening, at its own todo event, after the gap, and once more offline")
        assertEquals(listOf(false, true, true, false, true), seen.map { it.loaded })
        assertEquals(true, seen[3].reconnecting)
        assertEquals(1, seen[1].todo?.done)
        job.cancel()
    }

    @Test fun theTodoIsReadOnceWithoutTheFeedAndNeverWithoutTodos() = runTest {
        val todos = FakeTodos()
        val features = FakeFeatures(setOf(Features.TODOS))
        val feed = OrgFeed(FakeEventRepository(), features, backgroundScope)
        assertEquals(listOf(TodoUpdate(null)), FollowTodoUseCase(todos, feed, features, fakeGuard())("a1", "s1").toList(), "A session whose agent has no todo yet")
        features.features = emptySet()
        assertEquals(emptyList(), FollowTodoUseCase(todos, feed, features, fakeGuard())("a1", "s1").toList())
        todos.failure = AppException.Unauthorized()
        features.features = setOf(Features.TODOS)
        assertFailsWith<AppException.Unauthorized> { FollowTodoUseCase(todos, feed, features, fakeGuard())("a1", "s1").toList() }
    }

    @Test fun theCounterLeavesOutDroppedItemsAndFlagsAMostlyAttestedList() {
        val todo = SessionTodo("s1", "Plan", "active", listOf(
            item("a", TodoItemState.DONE, unverified = true), item("b", TodoItemState.DOING), item("c", TodoItemState.DROPPED),
            item("d", TodoItemState.DONE, unverified = true), item("e", TodoItemState.TODO),
        ), 1L)
        assertEquals(2, todo.done)
        assertEquals(4, todo.total)
        assertEquals("b", todo.current?.id)
        assertTrue(todo.mostlyAttested)
        assertFalse(todo.copy(items = todo.items.map { it.copy(unverified = false) }).mostlyAttested)
    }
}
