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

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
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
                state.update { it.copy(entry = entry, loading = false, gone = false) }
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
