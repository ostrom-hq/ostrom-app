// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/HomeViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.requireSupportedApi
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase

data class HomeUiState(
    val account: Account? = null,
    /** What the account's server speaks and serves; null until it answered once. */
    val capabilities: ServerCapabilities? = null,
    /** The server speaks another API version than this app: the app is blocked until the person signs out. */
    val incompatible: Incompatible? = null,
    val refreshing: Boolean = false,
    val error: Failure? = null,
) {
    /** Whether the server announces [feature]; false until the capabilities are read. */
    fun serves(feature: String): Boolean = capabilities?.features?.contains(feature) == true
}

/** A server this app does not speak. [apiVersion] is null for a server too old to say which version it speaks. */
data class Incompatible(val apiVersion: Int?, val supported: Int = Features.SUPPORTED_API_VERSION)

/**
 * The active account, re-read from the server when it changes (a revoked session shows as signed out), with the
 * server's capabilities, read again when the app comes back to the foreground ([onForeground]) at most every
 * [RECHECK_AFTER_MILLIS]. A server that cannot be reached changes nothing and never signs out.
 */
class HomeViewModel(
    private val accounts: AccountRepository,
    private val refreshAccount: RefreshAccountUseCase,
    private val features: FeaturesRepository,
    private val clock: Clock,
) : ViewModel() {
    private val status = MutableStateFlow(HomeUiState())
    private var checkedAt = Long.MIN_VALUE
    val uiState: StateFlow<HomeUiState> = combine(accounts.accounts, accounts.activeAccountId, status) { list, active, status ->
        status.copy(account = list.firstOrNull { it.id == active })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        viewModelScope.launch {
            accounts.activeAccountId.collectLatest { id -> if (id != null) refresh(id) else status.value = HomeUiState() }
        }
    }

    /** The app came back to the foreground: the server is asked again if it was not asked recently. */
    fun onForeground() {
        val id = accounts.activeAccountId.value ?: return
        if (status.value.refreshing || clock.nowMillis() - checkedAt < RECHECK_AFTER_MILLIS) return
        viewModelScope.launch { refresh(id) }
    }

    private suspend fun refresh(id: String) {
        checkedAt = clock.nowMillis()
        status.value = HomeUiState(capabilities = features.cached(id), refreshing = true)
        status.value = try {
            val account = refreshAccount(id)
            if (account == null || !account.signedIn) {
                HomeUiState()
            } else {
                val capabilities = features.capabilities(id, refresh = true)
                requireSupportedApi(capabilities.apiVersion)
                HomeUiState(capabilities = capabilities)
            }
        } catch (error: CancellationException) {
            // A newer account replaced this refresh, or its client was closed by a sign-out: no spinner left behind.
            status.value = HomeUiState(capabilities = features.cached(id))
            throw error
        } catch (error: AppException.UnsupportedApi) {
            HomeUiState(incompatible = Incompatible(error.apiVersion))
        } catch (error: Exception) {
            HomeUiState(capabilities = features.cached(id), error = error.userMessage())
        }
    }

    companion object {
        /** The server is not asked again when the app comes back to the foreground within this time. */
        const val RECHECK_AFTER_MILLIS = 10 * 60_000L
    }
}
