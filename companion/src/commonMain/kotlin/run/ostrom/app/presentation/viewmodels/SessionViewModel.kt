// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionViewModel (todo panel) (Apache-2.0), companion/.../presentation/viewmodels/SessionViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.usecases.sessions.GetSessionUseCase
import run.ostrom.app.domain.usecases.sessions.SessionDetails
import run.ostrom.app.domain.usecases.todos.FollowTodoUseCase
import run.ostrom.app.presentation.navigation.SessionRoute

data class SessionUiState(
    val details: SessionDetails? = null,
    val loading: Boolean = true,
    /** The session is gone or hidden from this member. */
    val gone: Boolean = false,
    val error: Failure? = null,
    /** The session's todo, read last; null while its agent has none (or before the first read, see [todoLoaded]). */
    val todo: SessionTodo? = null,
    val todoLoaded: Boolean = false,
    val todoReconnecting: Boolean = false,
)

/**
 * One session's record: read on open and on [load] (refresh, retry). While shown ([start], [stop]) its todo follows
 * the app's one feed ([FollowTodoUseCase]): read when the feed opens, after a gap and at each `cloud.todo` of the
 * session; once from a server without the feed.
 */
class SessionViewModel(
    private val route: SessionRoute,
    private val getSession: GetSessionUseCase,
    private val followTodo: FollowTodoUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = state.asStateFlow()
    private var loading: Job? = null
    private var todo: Job? = null
    private var started = false

    init { load() }

    fun start() { started = true; followTodo() }

    fun stop() {
        started = false
        todo?.cancel(); todo = null
        state.update { it.copy(todoReconnecting = false) }
    }

    /** The record again, and the todo with it. */
    fun load() {
        state.update { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val details = getSession(route.accountId, route.sessionId)
                state.update { it.copy(details = details, loading = false, gone = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppException.NotFound) {
                state.update { it.copy(loading = false, gone = true, error = error.userMessage()) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
        if (started) followTodo()
    }

    private fun followTodo() {
        todo?.cancel()
        todo = viewModelScope.launch {
            try {
                followTodo(route.accountId, route.sessionId).collect { update ->
                    state.update {
                        if (update.loaded) it.copy(todo = update.todo, todoLoaded = true, todoReconnecting = false)
                        else it.copy(todoReconnecting = update.reconnecting)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The feed ended (a refusal, a signed-out account): the todo shown stays.
                state.update { it.copy(todoReconnecting = false) }
            }
        }
    }
}
