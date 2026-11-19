// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/SessionsViewModel.kt; see NOTICE.
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
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionsUseCase

data class CloudSessionsUiState(
    val account: Account? = null,
    /** Newest first: the first page, then the older ones read as the list nears its end. */
    val sessions: List<CloudSession> = emptyList(),
    /** The cursor of the older page; null when everything is there. */
    val next: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** The server stopped offering cloud sessions. */
    val unavailable: Boolean = false,
    val error: Failure? = null,
    /** The [error] is an older page's: a retry reads that page again, not the first one. */
    val moreFailed: Boolean = false,
    /** The server has remote sessions (`runners.cloud.remote.v1`): each row says where it runs. */
    val remoteSessions: Boolean = false,
) {
    val empty: Boolean get() = loaded && !loading && sessions.isEmpty()
}

/**
 * The active account's cloud sessions, newest first, keyset-paged. Ostrom publishes no cloud session event: the list
 * loads each time it shows ([start]) and on [reload], never polled.
 */
class CloudSessionsViewModel(
    private val accounts: AccountRepository,
    private val loadSessions: LoadCloudSessionsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(CloudSessionsUiState())
    val uiState: StateFlow<CloudSessionsUiState> = state.asStateFlow()
    private var started = false
    private var load: Job? = null

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    load?.cancel()
                    state.value = CloudSessionsUiState(account = account)
                    if (started) reload()
                }
        }
    }

    /** The screen shows: the first page again. */
    fun start() { started = true; reload() }

    fun stop() { started = false }

    /** After a failure: the page that failed again. */
    fun retry() = if (state.value.moreFailed) loadMore() else reload()

    /** The first page again; the older pages already read stay after it. */
    fun reload() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        load?.cancel()
        state.update { it.copy(loading = true, loadingMore = false, error = null, moreFailed = false) }
        load = viewModelScope.launch { page(account.id, before = null) }
    }

    /** The next page, once: a request already running is not doubled. */
    fun loadMore() {
        val current = state.value
        val account = current.account?.takeIf { it.signedIn } ?: return
        val before = current.next ?: return
        if (load!!.isActive) return // a cursor exists only once a page was read, which always went through [load]
        state.update { it.copy(loadingMore = true, error = null, moreFailed = false) }
        load = viewModelScope.launch { page(account.id, before) }
    }

    private suspend fun page(accountId: String, before: String?) {
        try {
            val page = loadSessions(accountId, before)
            state.update { current ->
                if (current.account?.id != accountId) return@update current
                if (before != null) {
                    val known = current.sessions.mapTo(HashSet()) { it.id }
                    return@update current.copy(sessions = current.sessions + page.sessions.filter { known.add(it.id) }, next = page.next, loadingMore = false)
                }
                // A return or a refresh after older pages were read: the first page replaces the rows it covers, and the rows
                // older than its last one stay after it, so the list never collapses to its first page.
                val boundary = page.sessions.lastOrNull()?.createdAt
                val fresh = page.sessions.mapTo(HashSet()) { it.id }
                val older = if (page.next == null || boundary == null) emptyList()
                else current.sessions.filter { it.id !in fresh && (it.createdAt ?: Long.MIN_VALUE) < boundary }
                current.copy(sessions = page.sessions + older, next = if (older.isEmpty()) page.next else current.next, loaded = true, loading = false,
                    loadingMore = false, unavailable = false, remoteSessions = page.remoteSessions)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppException.Unsupported) {
            state.update { if (it.account?.id != accountId) it else it.copy(loading = false, loadingMore = false, loaded = true, unavailable = true) }
        } catch (error: Exception) {
            state.update { if (it.account?.id != accountId) it else it.copy(loading = false, loadingMore = false, error = error.userMessage(), moreFailed = before != null) }
        }
    }
}
