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
import run.ostrom.app.domain.models.ReceiptCheck
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

    /**
     * The [results] checked for [accountId], each on the row of the same receipt (the same content: the same id and
     * signature check the same way). Nothing changes once the list is another account's.
     */
    /** The first page read for [accountId]; nothing changes once the list is another account's. */
    internal fun withFirstPage(accountId: String, items: List<HistoryEntry>, next: String?): HistoryUiState =
        if (account?.id != accountId) this else copy(entries = items, next = next, loaded = true, refreshing = false, failed = false)

    internal fun withChecked(accountId: String, results: List<HistoryEntry>): HistoryUiState {
        if (account?.id != accountId) return this
        val checked = results.associateBy { it.raw }
        return copy(entries = entries.map { entry -> checked[entry.raw] ?: entry })
    }
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
    private var moreJob: Job? = null
    private var started = false

    /** The keys of the last refresh, which also check its older pages. */
    private var keys: List<ReceiptPublicKey> = emptyList()

    /**
     * The results found with [keys], by receipt id, each with the content it was found on: a receipt read again shows its
     * result at once instead of checking again, and one changed under the same id is checked again. Forgotten when the
     * keys change (a rotation) or the account does.
     */
    private val known = HashMap<String, Pair<JsonObject, ReceiptCheck>>()
    private var knownFor: String? = null

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn }
                .collect { account ->
                    job?.cancel()
                    moreJob?.cancel()
                    keys = emptyList()
                    known.clear()
                    knownFor = null
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

    fun retry() = if (state.value.moreFailed) loadMore() else load()

    private fun load() {
        val account = state.value.account?.takeIf { it.signedIn } ?: return
        job?.cancel()
        moreJob?.cancel()
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
                val fingerprint = trusted.map { "${it.kid}:${it.toRaw().contentHashCode()}" }.sorted().joinToString(",")
                if (fingerprint != knownFor) {
                    known.clear()
                    knownFor = fingerprint
                }
                val items = page.items.map(::withKnownResult)
                // An account change cancels this job first (see init), so the scope above throws instead of returning; the
                // guard holds whatever reaches here.
                state.update { it.withFirstPage(account.id, items, page.next) }
                check(account.id, items, trusted)
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
        val account = current.account ?: return
        if (!account.signedIn) return
        val cursor = current.next ?: return
        if (current.loadingMore || current.refreshing) return
        state.update { it.copy(loadingMore = true, moreFailed = false) }
        val trusted = keys
        moreJob = viewModelScope.launch {
            try {
                val page = loadReceipts(account.id, cursor)
                var added = emptyList<HistoryEntry>()
                state.update { now ->
                    if (now.account?.id != account.id || now.next != cursor) return@update now.copy(loadingMore = false)
                    val seen = now.entries.mapNotNullTo(HashSet()) { it.summary?.id }
                    added = page.items.filter { entry -> entry.summary?.let { seen.add(it.id) } ?: true }.map(::withKnownResult)
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

    /** The entry with the result already found for the same content under the same keys, else as it is. */
    private fun withKnownResult(entry: HistoryEntry): HistoryEntry {
        val (content, check) = entry.summary?.let { known[it.id] } ?: return entry
        return if (content == entry.raw) entry.copy(check = check) else entry
    }

    /** Verifies the [entries] not checked yet, off the main thread, then shows each result on the row of the same receipt. */
    private suspend fun check(accountId: String, entries: List<HistoryEntry>, trusted: List<ReceiptPublicKey>) {
        val pending = entries.filter { it.check == ReceiptCheck.Checking }
        if (pending.isEmpty()) return
        val results = checkReceipts(pending, trusted)
        results.forEach { entry -> entry.summary?.let { known[it.id] = entry.raw to entry.check } }
        // An account change cancels this first, but a check that ignores it (a verification that never suspends) still
        // answers: the guard keeps another account's rows as they are.
        state.update { it.withChecked(accountId, results) }
    }
}
