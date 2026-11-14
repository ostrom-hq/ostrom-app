// SPDX-License-Identifier: Apache-2.0
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.ReceiptCheck
import run.ostrom.app.domain.usecases.receipts.CheckReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.GetReceiptUseCase
import run.ostrom.app.domain.usecases.receipts.TrustedReceiptKeysUseCase
import run.ostrom.app.presentation.navigation.ReceiptRoute

data class ReceiptUiState(
    /** The receipt, checked on this device once it is read; reads as checking until then, never as verified. */
    val entry: HistoryEntry? = null,
    val loading: Boolean = true,
    /** The receipt is unknown, or hidden from this member. */
    val gone: Boolean = false,
    val error: Failure? = null,
)

/**
 * One receipt by its id, opened from a session's record: read on open and on [load] (refresh, retry), then verified on
 * this device against the server keys it trusts, as the History tab verifies each of its rows.
 */
class ReceiptViewModel(
    private val route: ReceiptRoute,
    private val getReceipt: GetReceiptUseCase,
    private val trustedKeys: TrustedReceiptKeysUseCase,
    private val checkReceipts: CheckReceiptsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(ReceiptUiState())
    val uiState: StateFlow<ReceiptUiState> = state.asStateFlow()
    private var loading: Job? = null

    /** The receipt's id, shown under it (also while it loads or when it is gone). */
    val receiptId: String get() = route.receiptId

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, error = null, gone = false) }
        // The latest load wins: an earlier, slower one never puts its older answer back.
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val (entry, trusted) = coroutineScope {
                    // The keys failing leaves the receipt unchecked ("no key"), never unshown.
                    val trusted = async {
                        try {
                            trustedKeys(route.accountId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    getReceipt(route.accountId, route.receiptId) to trusted.await()
                }
                // Read again unchanged (a refresh): a verified or rejected result stays, instead of reading as checking again;
                // one found without a key (or without Ed25519) is checked again.
                val shown = state.value.entry
                if (shown != null && shown.raw == entry.raw && (shown.check is ReceiptCheck.Verified || shown.check is ReceiptCheck.Rejected)) {
                    state.update { it.copy(loading = false) }
                    return@launch
                }
                state.update { it.copy(entry = entry, loading = false) }
                val checked = checkReceipts(listOf(entry), trusted).single()
                state.update { it.copy(entry = checked) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppException.NotFound) {
                state.update { it.copy(entry = null, loading = false, gone = true, error = error.userMessage()) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }
}
