// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/TransferViewModels.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SessionTransfer
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.usecases.transfers.LoadRecipientsUseCase
import run.ostrom.app.domain.usecases.transfers.TransferAction
import run.ostrom.app.domain.usecases.transfers.TransferListUseCase
import run.ostrom.app.domain.usecases.transfers.TransferRules
import run.ostrom.app.domain.usecases.transfers.TransferSessionUseCase
import run.ostrom.app.presentation.navigation.TransferRoute
import run.ostrom.app.presentation.navigation.TransfersRoute

/** A failure of a transfer screen: the server's refusal, worded by the screen from its error key, or the domain's sentence. */
sealed interface TransferError {
    data class Refused(val code: String) : TransferError
    data class Text(val text: Failure) : TransferError
}

internal fun Throwable.transferError(): TransferError =
    if (this is AppException.TransferRefused) TransferError.Refused(code) else TransferError.Text(userMessage())

data class TransferUiState(
    val loading: Boolean = true,
    /** Who can receive the session, by login. */
    val members: List<TeamMember> = emptyList(),
    val query: String = "",
    /** The member picked in the list. */
    val recipientId: String = "",
    val resume: Boolean = false,
    val sending: Boolean = false,
    val error: TransferError? = null,
    /** The transfer the server made: `accepted` for an administrator's, `offered` for a member's. */
    val done: SessionTransfer? = null,
) {
    /** The members matching the search, by login or ID. */
    val matches: List<TeamMember> get() {
        val q = query.trim()
        if (q.isEmpty()) return members
        return members.filter { it.username.contains(q, ignoreCase = true) || it.id.startsWith(q, ignoreCase = true) }
    }

    val recipient: TeamMember? get() = members.firstOrNull { it.id == recipientId }

    val canSend: Boolean get() = !sending && !loading && done == null && recipient != null && TransferRules.validId(recipientId.trim())
}

/**
 * The transfer form of one session. Its idempotency key is drawn once and kept while what is sent stays the same, so a
 * retry after a network failure replays the first transfer; another recipient or option draws a new one.
 */
class TransferViewModel(
    private val route: TransferRoute,
    private val loadRecipients: LoadRecipientsUseCase,
    private val transferSession: TransferSessionUseCase,
    private val accounts: AccountRepository,
    private val ids: IdGenerator,
) : ViewModel() {
    private val state = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = state.asStateFlow()
    private var key = ids.newId()

    init { reload() }

    fun reload() {
        if (state.value.sending) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val members = loadRecipients(route.accountId, route.ownerId)
                state.update { it.copy(members = members, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.transferError()) }
            }
        }
    }

    fun onQuery(value: String) = state.update { it.copy(query = value) }
    fun onRecipient(id: String) = edit { it.copy(recipientId = id) }
    fun onResume(value: Boolean) = edit { it.copy(resume = value) }

    private fun edit(change: (TransferUiState) -> TransferUiState) {
        val before = state.value
        if (before.sending || before.done != null) return
        val after = change(before).copy(error = null)
        if (after.request() != before.request()) key = ids.newId()
        state.value = after
    }

    fun send() {
        val current = state.value
        if (!current.canSend || accounts.find(route.accountId) == null) return
        state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val transfer = transferSession(route.accountId, route.sessionId, route.ownerId, current.request(), key)
                state.update { it.copy(sending = false, done = transfer) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(sending = false, error = error.transferError()) }
            }
        }
    }

    /** As sent: the identifier trimmed. */
    private fun TransferUiState.request(): TransferRequest = TransferSessionUseCase.sent(TransferRequest(recipientId, resume))
}

data class TransferListState(
    val transfers: List<SessionTransfer> = emptyList(),
    val nextBefore: String? = null,
    val loaded: Boolean = false,
)

data class TransfersUiState(
    val direction: TransferDirection = TransferDirection.INCOMING,
    val lists: Map<TransferDirection, TransferListState> = emptyMap(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Member logins by ID, where the server lists them to the member; otherwise IDs show. */
    val names: Map<String, String> = emptyMap(),
    val memberId: String = "",
    /** Transfers with a decision in flight. */
    val acting: Set<String> = emptySet(),
    val error: TransferError? = null,
) {
    val list: TransferListState get() = lists[direction] ?: TransferListState()
}

/** The member's incoming and outgoing transfers, and accepting, declining or withdrawing them. */
class TransfersViewModel(
    private val route: TransfersRoute,
    private val transfers: TransferListUseCase,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(TransfersUiState(memberId = account()?.memberId.orEmpty()))
    val uiState: StateFlow<TransfersUiState> = state.asStateFlow()
    private var namesLoaded = false

    init { refresh() }

    private fun account() = accounts.find(route.accountId)

    fun setDirection(direction: TransferDirection) {
        state.update { it.copy(direction = direction, error = null) }
        if (state.value.lists[direction]?.loaded != true) refresh()
    }

    /** The newest page of the shown direction again: a decision made elsewhere shows. */
    fun refresh() = load(clearError = true)

    private fun load(clearError: Boolean) {
        val direction = state.value.direction
        state.update { it.copy(loading = true, error = if (clearError) null else it.error) }
        viewModelScope.launch {
            try {
                if (!namesLoaded) {
                    // Names are a comfort: the list shows IDs without them.
                    try {
                        val names = transfers.names(route.accountId)
                        namesLoaded = true
                        state.update { it.copy(names = names) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                    }
                }
                val page = transfers.page(route.accountId, direction)
                state.update { it.copy(loading = false, lists = it.lists + (direction to TransferListState(page.transfers, page.nextBefore, loaded = true))) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.transferError()) }
            }
        }
    }

    fun loadMore() {
        val current = state.value
        val direction = current.direction
        val before = current.list.nextBefore ?: return
        if (current.loading || current.loadingMore) return
        state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = transfers.page(route.accountId, direction, before)
                state.update { s ->
                    val list = s.lists[direction] ?: TransferListState()
                    // A refresh meanwhile replaced the list: this page belongs to the old one.
                    if (list.nextBefore != before) return@update s.copy(loadingMore = false)
                    val known = list.transfers.map { it.id }.toSet()
                    s.copy(loadingMore = false, lists = s.lists + (direction to list.copy(transfers = list.transfers + page.transfers.filterNot { it.id in known }, nextBefore = page.nextBefore)))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loadingMore = false, error = error.transferError()) }
            }
        }
    }

    /** What the member may do with [transfer]. */
    fun actions(transfer: SessionTransfer): Set<TransferAction> = account()?.let { TransferRules.actions(it, transfer) } ?: emptySet()

    fun accept(transfer: SessionTransfer) = decide(transfer, TransferAction.ACCEPT) { transfers.accept(route.accountId, transfer.id) }
    fun decline(transfer: SessionTransfer) = decide(transfer, TransferAction.DECLINE) { transfers.decline(route.accountId, transfer.id) }
    fun cancel(transfer: SessionTransfer) = decide(transfer, TransferAction.CANCEL) { transfers.cancel(route.accountId, transfer.id) }

    private fun decide(transfer: SessionTransfer, action: TransferAction, call: suspend () -> SessionTransfer) {
        if (transfer.id in state.value.acting || action !in actions(transfer)) return
        state.update { it.copy(acting = it.acting + transfer.id, error = null) }
        viewModelScope.launch {
            try {
                val updated = call()
                state.update { s -> s.copy(lists = s.lists.mapValues { (_, list) -> list.copy(transfers = list.transfers.map { if (it.id == updated.id) updated else it }) }) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(error = error.transferError()) }
                // Refused because it changed meanwhile (decided, expired): show what it is now, under the refusal.
                if (error is AppException.TransferRefused) load(clearError = false)
            } finally {
                state.update { it.copy(acting = it.acting - transfer.id) }
            }
        }
    }
}
