// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion WaitsViewModel (Apache-2.0); see NOTICE.
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
import run.ostrom.app.domain.models.ActivityItem
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.spending.LoadActivityUseCase
import run.ostrom.app.domain.usecases.spending.LoadSpendingUseCase

data class SpendingUiState(
    val account: Account? = null,
    val period: SpendPeriod = SpendPeriod.MONTH,
    /** The report of [period]; null while another period loads, so a figure is never shown under the wrong period. */
    val report: SpendingReport? = null,
    val loading: Boolean = false,
    /** The last load failed: what is shown stays, and Retry loads again. */
    val error: Boolean = false,
)

/** The spending of the active account's organization, by week or month. Loaded on [start] (each return to the tab) and on a period change. */
class SpendingViewModel(private val accounts: AccountRepository, private val loadSpending: LoadSpendingUseCase) : ViewModel() {
    private val state = MutableStateFlow(SpendingUiState())
    val uiState: StateFlow<SpendingUiState> = state.asStateFlow()
    private var loading: Job? = null
    private var started = false

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    loading?.cancel()
                    state.value = SpendingUiState(account = account, period = state.value.period)
                    // Another account, or the same one signed in again, while the tab is shown: its spending loads.
                    if (started) load()
                }
        }
    }

    fun start() {
        started = true
        load()
    }

    fun setPeriod(period: SpendPeriod) {
        if (state.value.period == period) return
        state.update { it.copy(period = period, report = null) }
        load()
    }

    fun retry() = load()

    private fun load() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        val period = state.value.period
        loading?.cancel()
        state.update { it.copy(loading = true, error = false) }
        loading = viewModelScope.launch {
            try {
                val report = loadSpending(account.id, period)
                state.update { if (it.account?.id != account.id || it.period != period) it else it.copy(report = report, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // An expired session is shown by the signed-in shell; here, what is shown stays and Retry loads again.
                state.update { if (it.account?.id != account.id || it.period != period) it else it.copy(loading = false, error = true) }
            }
        }
    }
}

data class ActivityUiState(
    val account: Account? = null,
    /** Newest first: the first page, then what "load more" added. */
    val items: List<ActivityItem> = emptyList(),
    /** The cursor of the older page; null when everything is there. */
    val next: String? = null,
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    /** The last load failed: what is shown stays, and Retry loads again. */
    val error: Boolean = false,
)

/** The organization's activity, newest first, keyset-paged. Loaded from its first page on [start] (each return to the tab). */
class ActivityViewModel(private val accounts: AccountRepository, private val loadActivity: LoadActivityUseCase) : ViewModel() {
    private val state = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = state.asStateFlow()
    private var job: Job? = null
    private var started = false

    /** The last failure was an older page's: Retry asks that page again and keeps what is read. */
    private var moreFailed = false

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    job?.cancel()
                    state.value = ActivityUiState(account = account)
                    // Another account, or the same one signed in again, while the tab is shown: its feed loads.
                    if (started) load()
                }
        }
    }

    /** Loads the first page again; the older pages already read are replaced by it. */
    fun start() {
        started = true
        load()
    }

    fun retry() = if (moreFailed && state.value.next != null) loadMore() else load()

    private fun load() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        moreFailed = false
        job?.cancel()
        state.update { it.copy(refreshing = true, loadingMore = false, error = false) }
        job = viewModelScope.launch {
            try {
                val page = loadActivity(account.id)
                state.update { if (it.account?.id != account.id) it else it.copy(items = page.items, next = page.next, loaded = true, refreshing = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { if (it.account?.id != account.id) it else it.copy(refreshing = false, error = true) }
            }
        }
    }

    /** The older page, from the cursor the last page gave; its lines already shown are not repeated. */
    fun loadMore() {
        val current = state.value
        val account = current.account?.takeIf { it.signedIn } ?: return
        val cursor = current.next ?: return
        if (current.loadingMore || current.refreshing) return
        moreFailed = false
        state.update { it.copy(loadingMore = true, error = false) }
        job = viewModelScope.launch {
            try {
                val page = loadActivity(account.id, cursor)
                state.update { now ->
                    if (now.account?.id != account.id || now.next != cursor) return@update now.copy(loadingMore = false)
                    val seen = now.items.mapTo(HashSet()) { it.id }
                    now.copy(items = now.items + page.items.filter { seen.add(it.id) }, next = page.next, loadingMore = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.account?.id == account.id) moreFailed = true
                state.update { if (it.account?.id != account.id) it else it.copy(loadingMore = false, error = true) }
            }
        }
    }
}

