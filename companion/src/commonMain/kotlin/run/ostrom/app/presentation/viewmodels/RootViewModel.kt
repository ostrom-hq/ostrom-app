// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/RootViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import run.ostrom.app.domain.models.ThemePreference
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.PreferencesRepository

/** The start destination (adding a server when there is no account yet) and the theme. */
class RootViewModel(accounts: AccountRepository, preferences: PreferencesRepository) : ViewModel() {
    val hasAccounts: StateFlow<Boolean> = accounts.accounts
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, accounts.accounts.value.isNotEmpty())
    val theme: StateFlow<ThemePreference> = preferences.theme
}
