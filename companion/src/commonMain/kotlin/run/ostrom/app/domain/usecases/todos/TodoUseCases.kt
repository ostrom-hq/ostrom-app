// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/todos/TodoUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.todos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.TodoRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.events.signals

/**
 * What a screen shows of a session's todo: the list read last ([loaded] with a null [todo]: the agent has none yet),
 * and whether the live feed is cut. [loaded] false only reports the connection's state: what is shown stays.
 */
data class TodoUpdate(val todo: SessionTodo?, val loaded: Boolean = true, val reconnecting: Boolean = false)

/**
 * Keeps a session's todo current through the app's one feed ([OrgFeed]): the list is read when the feed opens (so
 * nothing is missed between loading and following), after a gap, and at each `cloud.todo` event of this session.
 * Without the feed the list is read once, and on return to the screen. A server without `todos.v1` yields nothing.
 * A read that fails (offline, a busy server) keeps what is shown.
 */
class FollowTodoUseCase(
    private val todos: TodoRepository,
    private val feed: OrgFeed,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
) {
    operator fun invoke(accountId: String, sessionId: String): Flow<TodoUpdate> = guard.flow(accountId, flow {
        if (Features.TODOS !in features.capabilities(accountId).features || sessionId.isBlank()) return@flow
        suspend fun load(): TodoUpdate? = try {
            TodoUpdate(todos.todo(accountId, sessionId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: AppException.Unauthorized) {
            throw denied
        } catch (_: Exception) {
            // Not readable now (offline, a 5xx): the list on screen stays as it is.
            null
        }
        feed.signals(accountId, features, setOf(OrgChange.TODO_UPDATED)).collect { signal ->
            when (signal) {
                FeedSignal.Reload -> load()?.let { emit(it) }
                is FeedSignal.Changed -> if (signal.change.subjectId == sessionId) load()?.let { emit(it) }
                is FeedSignal.Connection -> emit(TodoUpdate(todo = null, loaded = false, reconnecting = signal.reconnecting))
            }
        }
    })
}
