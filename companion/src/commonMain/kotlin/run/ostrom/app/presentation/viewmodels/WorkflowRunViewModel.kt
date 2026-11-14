// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/WorkflowRunViewModel.kt; see NOTICE.
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
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.workflows.FollowWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.concernsRun
import run.ostrom.app.presentation.navigation.WorkflowRunRoute

data class WorkflowRunUiState(
    val run: WorkflowRun? = null,
    val loading: Boolean = true,
    val gone: Boolean = false,
    val reconnecting: Boolean = false,
    val error: Failure? = null,
)

/**
 * One run with its visits: read on open and on [load]; while shown ([start], [stop]) read again at each change of the
 * run (or of a sub-workflow's run it shows) the app's one feed announces, when it opens and after a gap.
 */
class WorkflowRunViewModel(
    private val route: WorkflowRunRoute,
    private val getRun: GetWorkflowRunUseCase,
    private val followRuns: FollowWorkflowRunsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowRunUiState())
    val uiState: StateFlow<WorkflowRunUiState> = state.asStateFlow()
    private var loading: Job? = null
    private var follow: Job? = null

    init { load() }

    fun start() {
        follow?.cancel()
        follow = viewModelScope.launch {
            try {
                followRuns(route.accountId).collect { signal ->
                    when (signal) {
                        FeedSignal.Reload -> load()
                        is FeedSignal.Changed -> if (signal.change.concernsRun(state.value.run, route.runId)) load()
                        is FeedSignal.Connection -> state.update { it.copy(reconnecting = signal.reconnecting) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(reconnecting = false) }
            }
        }
    }

    fun stop() {
        follow?.cancel(); follow = null
        state.update { it.copy(reconnecting = false) }
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val run = getRun(route.accountId, route.runId)
                state.update { it.copy(run = run, loading = false, gone = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppException.NotFound) {
                state.update { it.copy(loading = false, gone = true, error = error.userMessage()) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }
}
