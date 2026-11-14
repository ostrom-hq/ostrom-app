// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/WorkflowsViewModels.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.usecases.workflows.CanManageRunsUseCase
import run.ostrom.app.domain.usecases.workflows.DeleteWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowEditRightsUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowsUseCase
import run.ostrom.app.domain.usecases.workflows.StartWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.confirmationLink
import run.ostrom.app.presentation.navigation.WorkflowRoute
import run.ostrom.app.presentation.navigation.WorkflowStartRoute

data class WorkflowsUiState(
    val account: Account? = null,
    /** By name; null before the first read. */
    val workflows: List<Workflow>? = null,
    val loading: Boolean = false,
    val error: Failure? = null,
    /** Who may decide workflows; null until read or when unreadable (New then shows, and a refusal is worded). */
    val rights: WorkflowEditRights? = null,
    /** An owner or an admin (or a role that cannot be read): the editors' rule shows to them, read only. */
    val administers: Boolean = false,
) {
    val empty: Boolean get() = workflows?.isEmpty() == true && !loading

    /** New shows unless the settings say the person may not decide workflows. */
    val canCreate: Boolean get() = account?.signedIn == true && rights?.mayDecide != false
}

/** The saved workflows of the active account, read only. They have no feed event: read at each return ([start]) and on [reload]. */
class WorkflowsViewModel(
    private val accounts: AccountRepository,
    private val loadWorkflows: LoadWorkflowsUseCase,
    private val loadRights: LoadWorkflowEditRightsUseCase,
    private val canManageRuns: CanManageRunsUseCase,
) : ViewModel() {
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
                val rights = async { loadRights.optional(account.id) }
                val administers = async { canManageRuns(account.id) }
                val workflows = loadWorkflows(account.id)
                state.update { if (it.account?.id != account.id) it else it.copy(workflows = workflows, loading = false) }
                val read = rights.await()
                val admin = administers.await()
                state.update { if (it.account?.id != account.id) it else it.copy(rights = read, administers = admin) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { if (it.account?.id != account.id) it else it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }
}

/** The editing rights, null when they cannot be read: the actions then show and the server's refusal is worded. */
private suspend fun LoadWorkflowEditRightsUseCase.optional(accountId: String): WorkflowEditRights? = try {
    invoke(accountId)
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

data class WorkflowUiState(
    val workflow: Workflow? = null,
    val loading: Boolean = true,
    val gone: Boolean = false,
    val error: Failure? = null,
    /** The person may start and cancel runs (an owner or an admin); false until known, so Start never flashes. */
    val managesRuns: Boolean = false,
    /** The person may decide workflows; true until the settings say otherwise, or when unreadable. */
    val mayDecide: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: Failure? = null,
    /** The deletion waits for an owner's or an admin's confirmation on the console. */
    val deleteHeld: HeldChange? = null,
    /** Deleted: the screen goes back. */
    val deleted: Boolean = false,
) {
    /** Edit shows for who may decide, on a workflow whose every node kind this app knows. */
    val canEdit: Boolean get() = mayDecide && workflow?.nodes?.none { it.kind == NodeKind.UNKNOWN } == true

    val canDelete: Boolean get() = mayDecide && workflow != null && !deleting && deleteHeld == null && !deleted

    /** Start shows on an accepted workflow, for who may start it. */
    val canStart: Boolean get() = managesRuns && workflow?.runnable == true
}

/** One workflow, read only, with Start: read on open and on [load]. Its runs are a [WorkflowRunsViewModel] of their own. */
class WorkflowViewModel(
    private val route: WorkflowRoute,
    private val getWorkflow: GetWorkflowUseCase,
    private val canManageRuns: CanManageRunsUseCase,
    private val loadRights: LoadWorkflowEditRightsUseCase,
    private val deleteWorkflow: DeleteWorkflowUseCase,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowUiState())
    val uiState: StateFlow<WorkflowUiState> = state.asStateFlow()
    private var loading: Job? = null
    // The role is read alongside the first read of the workflow, once per page: it changes rarely, and never fails.
    private val rights: Deferred<Boolean> = viewModelScope.async { canManageRuns(route.accountId) }

    init {
        load()
        viewModelScope.launch { state.update { it.copy(mayDecide = loadRights.optional(route.accountId)?.mayDecide != false) } }
    }

    /** Deletes the workflow (asked first by the screen); a held deletion says so and offers its console page. */
    fun delete() {
        if (!state.value.canDelete) return
        state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            try {
                when (val write = deleteWorkflow(route.accountId, route.workflowId)) {
                    is WorkflowWrite.Applied -> state.update { it.copy(deleting = false, deleted = true) }
                    is WorkflowWrite.Held -> {
                        val origin = accounts.find(route.accountId)?.origin.orEmpty()
                        state.update { it.copy(deleting = false, deleteHeld = HeldChange(confirmationLink(origin, write.confirmUrl), write.expiresAt)) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(deleting = false, deleteError = error.userMessage()) }
            }
        }
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val workflow = getWorkflow(route.accountId, route.workflowId)
                state.update { it.copy(workflow = workflow, loading = false, gone = false) }
                if (workflow.runnable) state.update { it.copy(managesRuns = rights.await()) }
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

data class WorkflowStartUiState(
    val workflow: Workflow? = null,
    val loading: Boolean = true,
    val gone: Boolean = false,
    val error: Failure? = null,
    /** What was typed, by input id; an input not typed shows its default. */
    val inputs: Map<String, String> = emptyMap(),
    val starting: Boolean = false,
    /** The failure of the start, apart from the form's load. */
    val startError: Failure? = null,
    /** The run that started: the screen moves to it. */
    val started: WorkflowRun? = null,
) {
    fun valueOf(inputId: String): String = inputs[inputId] ?: workflow?.inputs?.firstOrNull { it.id == inputId }?.default.orEmpty()

    /** Every required input has a value (typed, or its default). */
    val filled: Boolean get() = workflow?.inputs?.all { !it.required || valueOf(it.id).isNotBlank() } == true

    val canStart: Boolean get() = workflow != null && filled && !starting && started == null
}

/**
 * The form that starts a run: a field for each input of the workflow (its label, whether it is required, its default,
 * its maximum length), then Start, which lands on the new run. The server checks the inputs again and its refusals
 * (not an owner or an admin, not accepted, too many runs) are worded by key.
 */
class WorkflowStartViewModel(
    private val route: WorkflowStartRoute,
    private val getWorkflow: GetWorkflowUseCase,
    private val startRun: StartWorkflowRunUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowStartUiState())
    val uiState: StateFlow<WorkflowStartUiState> = state.asStateFlow()
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

    fun onInput(id: String, value: String) {
        val max = state.value.workflow?.inputs?.firstOrNull { it.id == id }?.maxLength ?: return
        state.update { it.copy(inputs = it.inputs + (id to value.take(max)), startError = null) }
    }

    fun start() {
        val current = state.value
        val workflow = current.workflow ?: return
        if (!current.canStart) return
        state.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            try {
                val run = startRun(route.accountId, workflow, current.inputs)
                state.update { it.copy(starting = false, started = run) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(starting = false, startError = error.userMessage()) }
            }
        }
    }
}
