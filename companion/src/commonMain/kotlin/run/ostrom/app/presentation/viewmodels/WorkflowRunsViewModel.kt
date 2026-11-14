// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/WorkflowRunsViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.RunFilter
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.workflows.FollowWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.concernsRunsOf
import run.ostrom.app.presentation.navigation.WorkflowRoute

data class WorkflowRunsUiState(
    val accountId: String? = null,
    val filter: RunFilter = RunFilter.ALL,
    /** Newest first; null before the first read. */
    val runs: List<WorkflowRun>? = null,
    val next: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val reconnecting: Boolean = false,
    val error: Failure? = null,
) {
    val empty: Boolean get() = runs?.isEmpty() == true && !loading
}

/**
 * Runs, newest first, in one [RunFilter]: the active account's own (the Workflows tab, [of] null), or one workflow's
 * ([of]). Kept current while shown ([start], [stop]) by the app's one feed ([FollowWorkflowRunsUseCase]): a load when it
 * opens, after a gap and at each run event of the list; once from a server without the feed; again on [reload].
 */
class WorkflowRunsViewModel(
    private val of: WorkflowRoute?,
    accounts: AccountRepository,
    private val loadRuns: LoadWorkflowRunsUseCase,
    private val followRuns: FollowWorkflowRunsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowRunsUiState())
    val uiState: StateFlow<WorkflowRunsUiState> = state.asStateFlow()
    private var started = false
    private var follow: Job? = null
    private var load: Job? = null
    private var pendingReload: Job? = null

    init {
        viewModelScope.launch {
            val accountIds = if (of != null) flowOf(of.accountId)
            else combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id }?.takeIf(Account::signedIn)?.id }
            accountIds.distinctUntilChanged().collect { accountId ->
                load?.cancel()
                state.value = WorkflowRunsUiState(accountId = accountId, filter = state.value.filter)
                restart()
            }
        }
    }

    fun start() { started = true; restart() }

    fun stop() {
        started = false
        follow?.cancel(); follow = null
        pendingReload?.cancel(); pendingReload = null
        state.update { it.copy(reconnecting = false) }
    }

    fun setFilter(filter: RunFilter) {
        if (state.value.filter == filter) return
        state.update { it.copy(filter = filter, runs = null, next = null) }
        reload()
    }

    /** The first page again; the latest load wins. */
    fun reload() {
        val accountId = state.value.accountId ?: return
        load?.cancel()
        state.update { it.copy(loading = true, loadingMore = false, error = null) }
        load = viewModelScope.launch { page(accountId, state.value.filter, before = null) }
    }

    fun loadMore() {
        val current = state.value
        val accountId = current.accountId ?: return
        val before = current.next ?: return
        if (load?.isActive == true) return
        state.update { it.copy(loadingMore = true, error = null) }
        load = viewModelScope.launch { page(accountId, current.filter, before) }
    }

    private fun reloadSoon() {
        if (pendingReload?.isActive == true) return
        pendingReload = viewModelScope.launch { delay(BURST_MILLIS); reload() }
    }

    private fun restart() {
        follow?.cancel(); follow = null
        val accountId = state.value.accountId
        if (!started || accountId == null) return
        follow = viewModelScope.launch {
            try {
                followRuns(accountId).collect { signal ->
                    when (signal) {
                        FeedSignal.Reload -> reload()
                        // The event names the run and its status only: the list is read again, once for a burst (an
                        // organization's runs can update many times a second; a reload per event would never finish).
                        is FeedSignal.Changed -> if (signal.change.concernsRunsOf(of?.workflowId)) reloadSoon()
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

    private suspend fun page(accountId: String, filter: RunFilter, before: String?) {
        try {
            val page = loadRuns(accountId, of?.workflowId, filter, before)
            state.update { current ->
                if (current.accountId != accountId || current.filter != filter) return@update current
                if (before == null) return@update current.copy(runs = page.runs, next = page.next, loading = false, loadingMore = false)
                val known = current.runs.orEmpty().mapTo(HashSet()) { it.id }
                current.copy(runs = current.runs.orEmpty() + page.runs.filter { known.add(it.id) }, next = page.next, loadingMore = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { if (it.accountId != accountId) it else it.copy(loading = false, loadingMore = false, error = error.userMessage()) }
        }
    }
}

/** How long the runs list waits for the rest of a burst of run events before it reads again. */
private const val BURST_MILLIS = 300L
