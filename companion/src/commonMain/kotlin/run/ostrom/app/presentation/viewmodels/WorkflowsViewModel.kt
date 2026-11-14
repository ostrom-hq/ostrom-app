// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/WorkflowsViewModels.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.workflows.GetWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowsUseCase
import run.ostrom.app.presentation.navigation.WorkflowRoute

data class WorkflowsUiState(
    val account: Account? = null,
    /** By name; null before the first read. */
    val workflows: List<Workflow>? = null,
    val loading: Boolean = false,
    val error: Failure? = null,
) {
    val empty: Boolean get() = workflows?.isEmpty() == true && !loading
}

/** The saved workflows of the active account, read only. They have no feed event: read at each return ([start]) and on [reload]. */
class WorkflowsViewModel(private val accounts: AccountRepository, private val loadWorkflows: LoadWorkflowsUseCase) : ViewModel() {
    private val state = MutableStateFlow(WorkflowsUiState())
    val uiState: StateFlow<WorkflowsUiState> = state.asStateFlow()
    private var load: Job? = null
    private var started = false

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    load?.cancel()
                    state.value = WorkflowsUiState(account = account)
                    if (started) reload()
                }
        }
    }

    fun start() { started = true; reload() }

    fun reload() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        load?.cancel()
        state.update { it.copy(loading = true, error = null) }
        load = viewModelScope.launch {
            try {
                val workflows = loadWorkflows(account.id)
                state.update { if (it.account?.id != account.id) it else it.copy(workflows = workflows, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { if (it.account?.id != account.id) it else it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }
}

data class WorkflowUiState(val workflow: Workflow? = null, val loading: Boolean = true, val gone: Boolean = false, val error: Failure? = null)

/** One workflow, read only: read on open and on [load]. Its runs are a [WorkflowRunsViewModel] of their own. */
class WorkflowViewModel(private val route: WorkflowRoute, private val getWorkflow: GetWorkflowUseCase) : ViewModel() {
    private val state = MutableStateFlow(WorkflowUiState())
    val uiState: StateFlow<WorkflowUiState> = state.asStateFlow()
    private var loading: Job? = null

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val workflow = getWorkflow(route.accountId, route.workflowId)
                state.update { it.copy(workflow = workflow, loading = false, gone = false) }
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
