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
import run.ostrom.app.domain.usecases.push.NotificationTarget
import run.ostrom.app.domain.usecases.push.ResolveNotificationUseCase

/** The start destination (adding a server when there is no account yet), the theme, and the tapped notifications. */
class RootViewModel(
    private val accounts: AccountRepository,
    preferences: PreferencesRepository,
    private val resolveNotification: ResolveNotificationUseCase,
) : ViewModel() {
    val hasAccounts: StateFlow<Boolean> = accounts.accounts
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, accounts.accounts.value.isNotEmpty())
    val theme: StateFlow<ThemePreference> = preferences.theme

    /**
     * Where a tapped notification leads, its account made the active one; null when no signed-in account of this device
     * is the one it names.
     */
    fun openNotification(link: String): NotificationTarget? {
        val target = resolveNotification(link) ?: return null
        accounts.setActive(target.accountId)
        return target
    }
}
