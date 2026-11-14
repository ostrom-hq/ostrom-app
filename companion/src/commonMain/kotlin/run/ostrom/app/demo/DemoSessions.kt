// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's sessions and todos; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SessionGateAnswer
import run.ostrom.app.domain.models.SessionPage
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.models.TodoHookEvent
import run.ostrom.app.domain.models.TodoItem
import run.ostrom.app.domain.models.TodoItemState
import run.ostrom.app.domain.models.TodoProof
import run.ostrom.app.domain.repositories.SessionRepository
import run.ostrom.app.domain.repositories.TodoRepository

private const val MINUTE = 60_000L

/** The demo's session ids. */
internal object DemoSessionIds {
    const val OPEN = "demo-session-mill"
    const val ENDED = "demo-session-release"
    const val LOST = "demo-session-cards"
}

/** The demo's sessions, relative to [startedAt]: one open with a todo, one ended with its receipt and run, one lost. */
internal fun demoSessions(startedAt: Long): List<AgentSession> {
    val login = DemoContent.account().login
    return listOf(
        AgentSession(
            DemoSessionIds.OPEN, "analytical-engines/engine", "claude-code", "claude-opus", "charles", startedAt - 42 * MINUTE, null, null,
            startedAt - MINUTE, 37, 2.84, null, 1, null, null, lost = false, activity = "working",
            gateAnswers = listOf(SessionGateAnswer("demo-tool", "tool", "approved", login, startedAt - 20 * MINUTE, null)),
        ),
        AgentSession(
            DemoSessionIds.ENDED, "analytical-engines/engine", "codex", "gpt-5", login, startedAt - 26 * 60 * MINUTE, startedAt - 25 * 60 * MINUTE,
            "SessionEnd hook", startedAt - 25 * 60 * MINUTE, 64, 4.10, 0, 1, "demo-receipt-release", DemoWorkflowIds.RUN_DONE, lost = false, activity = null,
            gateAnswers = listOf(SessionGateAnswer("demo-merge", "merge", "approved", login, startedAt - 25 * 60 * MINUTE - 10 * MINUTE, null)),
        ),
        AgentSession(
            DemoSessionIds.LOST, "analytical-engines/cards", "claude-code", "claude-sonnet", "charles", startedAt - 50 * 60 * MINUTE, null, null,
            startedAt - 49 * 60 * MINUTE, 9, 0.35, null, 1, null, null, lost = true, activity = null,
        ),
    )
}

/** The demo's sessions from memory, newest first, in one page. */
class DemoSessionRepository(private val demo: DemoMode) : SessionRepository {
    override suspend fun sessions(accountId: String, open: Boolean?, before: String?): SessionPage {
        val all = demoSessions(demo.store().startedAt).filter { open == null || it.open == open }.map { it.copy(gateAnswers = emptyList()) }
        return SessionPage(if (before == null) all.sortedByDescending { it.startedAt } else emptyList(), null)
    }

    override suspend fun session(accountId: String, sessionId: String): AgentSession =
        demoSessions(demo.store().startedAt).firstOrNull { it.id == sessionId } ?: throw AppException.NotFound()
}

/** The open demo session's todo: done, doing, to do, an item added along the way and a dropped one. */
class DemoTodoRepository(private val demo: DemoMode) : TodoRepository {
    override suspend fun todo(accountId: String, sessionId: String): SessionTodo? {
        val startedAt = demo.store().startedAt
        if (sessionId != DemoSessionIds.OPEN) return null
        fun item(id: String, name: String, description: String, doneWhen: String, state: TodoItemState, proof: TodoProof? = null, addedLater: Boolean = false, reason: String? = null, unverified: Boolean = false, drop: String? = null) =
            TodoItem(id, name, description, doneWhen, state, addedLater, reason, proof, unverified, drop)
        return SessionTodo(
            sessionId = sessionId,
            reference = "Carry the tens in the mill: the plan in the \"Billing migration plan\" artifact, steps 1 to 3.",
            status = "active",
            items = listOf(
                item("carry", "Carry the tens", "Propagate the carry across the 40 digit wheels.", "The carriage tests pass", TodoItemState.DONE,
                    TodoProof(startedAt - 18 * MINUTE, true, "412 tests passed in CI", emptyList())),
                item("test40", "Test the carriage on 40 digits", "A property test over random 40 digit sums.", "CI green on the branch", TodoItemState.DOING),
                item("docs", "Document the carriage", "A page on how the carry moves.", "Artifact published", TodoItemState.TODO, addedLater = true, reason = "The review asked for it"),
                item("bench", "Benchmark the mill", "Compare with the previous carriage.", "Numbers in the pull request", TodoItemState.DROPPED, drop = "Not needed for this change"),
            ),
            updatedAt = startedAt - 2 * MINUTE,
            hookEvents = listOf(TodoHookEvent(startedAt - 30 * MINUTE, "hook_error", "The Stop hook timed out once; it let the turn end.")),
        )
    }
}

/** The demo account's sessions from memory, every other account's from its server. */
class DemoAwareSessionRepository(private val real: SessionRepository, private val demo: SessionRepository) : SessionRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun sessions(accountId: String, open: Boolean?, before: String?) = of(accountId).sessions(accountId, open, before)
    override suspend fun session(accountId: String, sessionId: String) = of(accountId).session(accountId, sessionId)
}

/** The demo account's todos from memory, every other account's from its server. */
class DemoAwareTodoRepository(private val real: TodoRepository, private val demo: TodoRepository) : TodoRepository {
    override suspend fun todo(accountId: String, sessionId: String) = (if (isDemo(accountId)) demo else real).todo(accountId, sessionId)
}
