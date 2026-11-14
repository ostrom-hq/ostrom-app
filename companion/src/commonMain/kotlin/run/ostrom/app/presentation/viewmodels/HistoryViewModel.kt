// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/ui/screens/history/useHistoryViewModel.ts, receiptChecks.ts and scheduleCheck.ts.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.receipts.CheckReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.LoadReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.TrustedReceiptKeysUseCase
import run.ostrom.core.receipts.ReceiptPublicKey

data class HistoryUiState(
    val account: Account? = null,
    /** Newest first: the first page, then the older ones; each checked on this device once it is read. */
    val entries: List<HistoryEntry> = emptyList(),
    /** The cursor of the older page; null when everything is there. */
    val next: String? = null,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** The last refresh failed: the receipts read before stay, and may be out of date. */
    val failed: Boolean = false,
    /** The older page failed: what is read stays, and Retry asks that page again. */
    val moreFailed: Boolean = false,
) {
    /** Receipts are shown, but the last refresh failed. */
    val stale: Boolean get() = failed && entries.isNotEmpty()
}

/**
 * The organization's signed receipts, newest first, keyset-paged, each verified on this device against the server keys
 * it trusts ([TrustedReceiptKeysUseCase]); a row reads as checking until then, never as verified. Loaded from its first
 * page on [start] (each return to the tab) and on [refresh] (pull), the keys read again each time (a rotation).
 */
class HistoryViewModel(
    private val accounts: AccountRepository,
    private val loadReceipts: LoadReceiptsUseCase,
    private val trustedKeys: TrustedReceiptKeysUseCase,
    private val checkReceipts: CheckReceiptsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = state.asStateFlow()
    private var job: Job? = null
    private var started = false

    /** The keys of the last refresh, which also check its older pages. */
    private var keys: List<ReceiptPublicKey> = emptyList()

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    job?.cancel()
                    keys = emptyList()
                    state.value = HistoryUiState(account = account)
                    if (started) load()
                }
        }
    }

    fun start() {
        started = true
        load()
    }

    fun refresh() = load()

    fun retry() = if (state.value.moreFailed && state.value.next != null) loadMore() else load()

    private fun load() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        job?.cancel()
        state.update { it.copy(refreshing = true, loadingMore = false, moreFailed = false) }
        job = viewModelScope.launch {
            try {
                val (page, trusted) = coroutineScope {
                    // The keys failing leaves every row unchecked ("no key"), never the list unshown.
                    val trusted = async {
                        try {
                            trustedKeys(account.id)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    loadReceipts(account.id) to trusted.await()
                }
                keys = trusted
                state.update { if (it.account?.id != account.id) it else it.copy(entries = page.items, next = page.next, loaded = true, refreshing = false, failed = false) }
                check(account.id, page.items, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { if (it.account?.id != account.id) it else it.copy(refreshing = false, failed = true) }
            }
        }
    }

    /** The older page, from the cursor the last page gave; a receipt already shown is not repeated. */
    fun loadMore() {
        val current = state.value
        val account = current.account?.takeIf { it.signedIn } ?: return
        val cursor = current.next ?: return
        if (current.loadingMore || current.refreshing) return
        state.update { it.copy(loadingMore = true, moreFailed = false) }
        val trusted = keys
        job = viewModelScope.launch {
            try {
                val page = loadReceipts(account.id, cursor)
                var added = emptyList<HistoryEntry>()
                state.update { now ->
                    if (now.account?.id != account.id || now.next != cursor) return@update now.copy(loadingMore = false)
                    val seen = now.entries.mapNotNullTo(HashSet()) { it.summary?.id }
                    added = page.items.filter { entry -> entry.summary?.id?.let(seen::add) ?: true }
                    now.copy(entries = now.entries + added, next = page.next, loadingMore = false)
                }
                check(account.id, added, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { if (it.account?.id != account.id) it else it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }

    /** Verifies [entries] off the main thread, then shows each result on the row of the same receipt. */
    private suspend fun check(accountId: String, entries: List<HistoryEntry>, trusted: List<ReceiptPublicKey>) {
        if (entries.isEmpty()) return
        val checked = checkReceipts(entries, trusted).associateBy { IdentityKey(it.raw) }
        state.update { now ->
            if (now.account?.id != accountId) now else now.copy(entries = now.entries.map { entry -> checked[IdentityKey(entry.raw)] ?: entry })
        }
    }

    /** The very object read from the server: a result found on one receipt is never shown on another. */
    private class IdentityKey(val raw: JsonObject) {
        override fun equals(other: Any?) = other is IdentityKey && other.raw === raw
        override fun hashCode() = raw.hashCode()
    }
}
