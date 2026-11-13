// SPDX-License-Identifier: AGPL-3.0-only
// The React Native app's useInboxViewModel.ts, shaped like TeamCode's companion view models (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.approvals.LoadApprovalsUseCase

/**
 * The inbox of the active account.
 *
 * @property loaded A list was read at least once: the rows are shown, even when a later refresh failed.
 * @property error The last refresh failed; with [loaded], the rows may be out of date ([stale]).
 * @property orgRequired The failure is the server asking which organization (`409 org_required`).
 */
data class InboxUiState(
    val accountId: String? = null,
    val pending: List<Approval> = emptyList(),
    val recent: List<Approval> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: Failure? = null,
    val orgRequired: Boolean = false,
) {
    val stale: Boolean get() = loaded && error != null
}

/**
 * What waits for the person's yes and what was answered lately, read again on [refresh] (pull to refresh, the refresh
 * button, the screen shown again). A row only opens its request: no answer from the list.
 */
class InboxViewModel(
    private val accounts: AccountRepository,
    private val load: LoadApprovalsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(InboxUiState())
    val uiState: StateFlow<InboxUiState> = state.asStateFlow()
    private var loading: Job? = null

    init {
        viewModelScope.launch {
            accounts.activeAccountId.collectLatest { id ->
                // Another account's list is never shown under this one.
                loading?.cancel()
                state.value = InboxUiState(accountId = id)
                if (id != null) read(id)
            }
        }
    }

    fun refresh() {
        val id = state.value.accountId ?: return
        if (state.value.refreshing) return
        loading = viewModelScope.launch { read(id) }
    }

    private suspend fun read(id: String) {
        state.update { it.copy(refreshing = true) }
        try {
            val queue = load(id)
            state.update { if (it.accountId != id) it else it.copy(pending = queue.pending, recent = queue.recent, loaded = true, refreshing = false, error = null, orgRequired = false) }
        } catch (error: CancellationException) {
            state.update { it.copy(refreshing = false) }
            throw error
        } catch (error: Exception) {
            state.update { if (it.accountId != id) it else it.copy(refreshing = false, error = error.userMessage(), orgRequired = error is AppException.OrganizationRequired) }
        }
    }
}
