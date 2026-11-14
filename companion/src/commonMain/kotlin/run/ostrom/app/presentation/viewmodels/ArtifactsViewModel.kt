// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/ArtifactsViewModel.kt; see NOTICE.
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
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.artifacts.LoadArtifactsUseCase

data class ArtifactsUiState(
    val account: Account? = null,
    /** Newest first: the first page, then the older ones read as the list nears its end. */
    val artifacts: List<Artifact> = emptyList(),
    /** The cursor of the older page; null when everything is there. */
    val next: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val query: String = "",
    val error: Failure? = null,
) {
    /** The loaded artifacts whose title or owner holds the search, ignoring case. */
    val shown: List<Artifact> get() = query.trim().let { q ->
        if (q.isEmpty()) artifacts else artifacts.filter { it.title.contains(q, ignoreCase = true) || it.owner.contains(q, ignoreCase = true) }
    }
}

/**
 * The gallery of the active account: the artifacts the person can read, newest first, a page at a time, searched in
 * the loaded titles. Loaded from its first page on [start] (each return to the tab).
 */
class ArtifactsViewModel(private val accounts: AccountRepository, private val loadArtifacts: LoadArtifactsUseCase) : ViewModel() {
    private val state = MutableStateFlow(ArtifactsUiState())
    val uiState: StateFlow<ArtifactsUiState> = state.asStateFlow()
    private var load: Job? = null
    private var started = false

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    load?.cancel()
                    state.value = ArtifactsUiState(account = account, query = state.value.query)
                    // Another account, or the same one signed in again, while the tab is shown: its artifacts load.
                    if (started) reload()
                }
        }
    }

    fun start() {
        started = true
        reload()
    }

    fun onQuery(query: String) = state.update { it.copy(query = query) }

    /** The first page again; the older pages already read are replaced by it. */
    fun reload() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        load?.cancel()
        state.update { it.copy(loading = true, loadingMore = false, error = null) }
        load = viewModelScope.launch { page(account.id, before = null) }
    }

    /** The next page, once: a request already running is not doubled. */
    fun loadMore() {
        val current = state.value
        val account = current.account?.takeIf { it.signedIn } ?: return
        val before = current.next ?: return
        if (load?.isActive == true) return
        state.update { it.copy(loadingMore = true, error = null) }
        load = viewModelScope.launch { page(account.id, before) }
    }

    private suspend fun page(accountId: String, before: String?) {
        try {
            val page = loadArtifacts(accountId, before)
            state.update { current ->
                if (current.account?.id != accountId) return@update current
                val rows = if (before == null) {
                    // Back on the tab after older pages were read: the first page is refreshed, the older rows kept
                    // after it, so the list and its place stay.
                    val fresh = page.artifacts.mapTo(HashSet()) { it.id }
                    val older = if (current.artifacts.size > page.artifacts.size) current.artifacts.filter { it.id !in fresh } else emptyList()
                    page.artifacts + older
                } else {
                    val known = current.artifacts.mapTo(HashSet()) { it.id }
                    current.artifacts + page.artifacts.filter { known.add(it.id) }
                }
                val next = if (before == null && current.artifacts.size > page.artifacts.size) current.next else page.next
                current.copy(artifacts = rows, next = next, loaded = true, loading = false, loadingMore = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { if (it.account?.id != accountId) it else it.copy(loading = false, loadingMore = false, error = error.userMessage()) }
        }
    }
}
