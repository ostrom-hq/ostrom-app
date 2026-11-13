// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/SettingsViewModel.kt; see NOTICE.
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.ThemePreference
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.PreferencesRepository
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.usecases.auth.OrganizationUseCases
import run.ostrom.app.domain.usecases.auth.SignOutUseCase
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.settings_signed_out_of
import run.ostrom.app.resources.settings_signed_out_storage
import run.ostrom.app.resources.settings_signed_out_unreachable

data class SettingsUiState(
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val appVersion: String = "",
    val supportedApiVersion: Int = Features.SUPPORTED_API_VERSION,
    /** The active account's server, as it answered last; null before it answered. */
    val server: ServerCapabilities? = null,
    /** The active account's organizations, once asked for ([SettingsViewModel.loadOrganizations]). */
    val organizations: List<Organization>? = null,
    /** False on a desktop without a keyring: tokens are not kept after the app quits. */
    val persistentCredentials: Boolean = true,
    val working: Boolean = false,
    val message: Failure? = null,
    /** The account [organizations] were read for: another account's list is never shown. */
    internal val organizationsOf: String? = null,
    /** The account [server] was read for. */
    internal val serverOf: String? = null,
) {
    val activeAccount: Account? get() = accounts.firstOrNull { it.id == activeAccountId }
}

class SettingsViewModel(
    private val accounts: AccountRepository,
    private val preferences: PreferencesRepository,
    private val features: FeaturesRepository,
    private val signOut: SignOutUseCase,
    private val organizations: OrganizationUseCases,
    private val credentials: CredentialStore,
    platform: PlatformInfo,
) : ViewModel() {
    private val status = MutableStateFlow(SettingsUiState(appVersion = platform.appVersion, persistentCredentials = credentials.persistent))

    // Read again whenever the accounts change: a sign-in that fell back to memory (locked keyring) shows at once.
    val uiState: StateFlow<SettingsUiState> = combine(accounts.accounts, accounts.activeAccountId, preferences.theme, status) { list, active, theme, status ->
        status.copy(
            accounts = list,
            activeAccountId = active,
            theme = theme,
            persistentCredentials = credentials.persistent,
            server = status.server.takeIf { active == status.serverOf },
            organizations = status.organizations.takeIf { active == status.organizationsOf },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, status.value)

    init {
        viewModelScope.launch {
            accounts.activeAccountId.collectLatest { id ->
                if (id == null) return@collectLatest
                try {
                    val server = features.capabilities(id)
                    status.update { it.copy(server = server, serverOf = id) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Offline: the versions stay unknown; the home screen says why.
                }
            }
        }
    }

    fun switchTo(account: Account) = accounts.setActive(account.id)
    fun setTheme(theme: ThemePreference) = preferences.setTheme(theme)
    fun dismissMessage() = status.update { it.copy(message = null) }

    /** Reads the active account's organizations, to choose the one its calls act in. */
    fun loadOrganizations() {
        val id = accounts.activeAccountId.value ?: return
        work {
            val list = organizations.list(id)
            status.update { it.copy(organizations = list, organizationsOf = id) }
            null
        }
    }

    fun chooseOrganization(organization: Organization) {
        val id = accounts.activeAccountId.value ?: return
        organizations.choose(id, organization)
    }

    fun signOut(account: Account) = work {
        when (signOut(account.id)) {
            SignOutUseCase.Result.REVOKED -> Failure("Signed out of ${account.login} on ${account.origin}.", Res.string.settings_signed_out_of, listOf(account.login, account.origin))
            SignOutUseCase.Result.SERVER_UNREACHABLE -> Failure("Signed out on this device. The server could not be reached; its session expires on its own.", Res.string.settings_signed_out_unreachable)
            SignOutUseCase.Result.STORAGE_REFUSED ->
                Failure("Signed out on this device, but its secure storage refused access: the session may stay valid until it expires.", Res.string.settings_signed_out_storage)
        }
    }

    private fun work(action: suspend () -> Failure?) {
        if (status.value.working) return
        status.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            val message = try {
                action()
            } catch (error: CancellationException) {
                // The screen is closing, or the account's client was closed under the call: never a stuck spinner.
                status.update { it.copy(working = false) }
                throw error
            } catch (error: Exception) {
                error.userMessage()
            }
            status.update { it.copy(working = false, message = message) }
        }
    }
}
