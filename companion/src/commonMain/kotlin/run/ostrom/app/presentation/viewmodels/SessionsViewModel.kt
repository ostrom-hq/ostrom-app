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
import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SessionFilter
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.sessions.FollowSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadSessionsUseCase

data class SessionsUiState(
    val account: Account? = null,
    val filter: SessionFilter = SessionFilter.ALL,
    /** Newest first: the first page, then the older ones read as the list nears its end. */
    val sessions: List<AgentSession> = emptyList(),
    /** The cursor of the older page; null when everything is there. */
    val next: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val reconnecting: Boolean = false,
    /** The server stopped offering sessions (the tab hides at the next capabilities read). */
    val unavailable: Boolean = false,
    val error: Failure? = null,
) {
    val empty: Boolean get() = loaded && !loading && sessions.isEmpty()
}

/**
 * The organization's sessions of the active account, open and ended, newest first, keyset-paged, in one [SessionFilter].
 * Kept current by [FollowSessionsUseCase] while the tab shows ([start], [stop]): a load when the app's one feed opens
 * and after a gap, or once from a server without the feed; again at each return and on [reload].
 */
class SessionsViewModel(
    private val accounts: AccountRepository,
    private val loadSessions: LoadSessionsUseCase,
    private val followSessions: FollowSessionsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = state.asStateFlow()
    private var started = false
    private var follow: Job? = null
    private var load: Job? = null

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    load?.cancel()
                    state.value = SessionsUiState(account = account, filter = state.value.filter)
                    restart()
                }
        }
    }

    fun start() { started = true; restart() }

    fun stop() {
        started = false
        follow?.cancel(); follow = null
        state.update { it.copy(reconnecting = false) }
    }

    fun setFilter(filter: SessionFilter) {
        if (state.value.filter == filter) return
        load?.cancel()
        state.update { it.copy(filter = filter, sessions = emptyList(), next = null, loaded = false) }
        reload()
    }

    /** The first page again; the older pages already read stay after it. */
    fun reload() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        load?.cancel()
        state.update { it.copy(loading = true, loadingMore = false, error = null) }
        load = viewModelScope.launch { page(account.id, state.value.filter, before = null) }
    }

    /** The next page, once: a request already running is not doubled. */
    fun loadMore() {
        val current = state.value
        val account = current.account?.takeIf { it.signedIn } ?: return
        val before = current.next ?: return
        if (load?.isActive == true) return
        state.update { it.copy(loadingMore = true, error = null) }
        load = viewModelScope.launch { page(account.id, current.filter, before) }
    }

    private fun restart() {
        follow?.cancel(); follow = null
        val account = state.value.account
        if (!started || account == null || !account.signedIn) return
        follow = viewModelScope.launch {
            try {
                followSessions(account.id).collect { signal ->
                    when (signal) {
                        FeedSignal.Reload -> reload()
                        is FeedSignal.Changed -> Unit
                        is FeedSignal.Connection -> state.update { it.copy(reconnecting = signal.reconnecting) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The follow ended (a refusal, a signed-out account): what is shown stays, a return loads again.
                state.update { it.copy(reconnecting = false) }
            }
        }
    }

    private suspend fun page(accountId: String, filter: SessionFilter, before: String?) {
        try {
            val page = loadSessions(accountId, filter, before)
            state.update { current ->
                if (current.account?.id != accountId || current.filter != filter) return@update current
                if (before != null) {
                    val known = current.sessions.mapTo(HashSet()) { it.id }
                    return@update current.copy(sessions = current.sessions + page.sessions.filter { known.add(it.id) }, next = page.next, loadingMore = false)
                }
                // A refresh after older pages were read: the first page replaces the rows it covers, and the rows older
                // than its last one stay after it, so a refresh never collapses a list scrolled further down.
                val boundary = page.sessions.lastOrNull()?.startedAt
                val fresh = page.sessions.mapTo(HashSet()) { it.id }
                val older = if (page.next == null || boundary == null) emptyList()
                else current.sessions.filter { it.id !in fresh && (it.startedAt ?: Long.MIN_VALUE) < boundary }
                current.copy(sessions = page.sessions + older, next = if (older.isEmpty()) page.next else current.next, loaded = true, loading = false,
                    loadingMore = false, unavailable = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppException.Unsupported) {
            state.update { if (it.account?.id != accountId) it else it.copy(loading = false, loadingMore = false, loaded = true, unavailable = true) }
        } catch (error: Exception) {
            state.update { if (it.account?.id != accountId) it else it.copy(loading = false, loadingMore = false, error = error.userMessage()) }
        }
    }
}
