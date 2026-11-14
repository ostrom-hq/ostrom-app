// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/viewmodels/NotificationPreferencesViewModel.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.NotificationToggle
import run.ostrom.app.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import run.ostrom.app.domain.usecases.notifications.SaveNotificationPreferencesUseCase
import run.ostrom.app.presentation.navigation.NotificationPreferencesRoute

data class NotificationPreferencesUiState(
    val loading: Boolean = true,
    /** Null until the server answered. */
    val preferences: NotificationPreferences? = null,
    /** A change is being saved: it is shown at once, and nothing else can change until the server answered. */
    val saving: Boolean = false,
    /** The last change was refused and undone. */
    val saveFailed: Boolean = false,
    /** The preferences could not be read. */
    val error: Failure? = null,
)

/**
 * The person's notification preferences on one account's server. A change shows at once and goes back if the server
 * refuses it; the server's answer is what stays shown. Quiet hours turn on from 22:00 to 07:00 in [timeZone].
 */
class NotificationPreferencesViewModel(
    private val route: NotificationPreferencesRoute,
    private val load: LoadNotificationPreferencesUseCase,
    private val save: SaveNotificationPreferencesUseCase,
    private val timeZone: () -> String,
) : ViewModel() {
    private val state = MutableStateFlow(NotificationPreferencesUiState())
    val uiState: StateFlow<NotificationPreferencesUiState> = state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (state.value.saving) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val preferences = load(route.accountId)
                state.update { it.copy(loading = false, preferences = preferences) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun toggle(toggle: NotificationToggle, on: Boolean) = change { it.with(toggle, on) }

    fun setQuietHours(on: Boolean) = change { it.withQuietHours(on, timeZone()) }

    private fun change(edit: (NotificationPreferences) -> NotificationPreferences) {
        val current = state.value
        val before = current.preferences ?: return
        if (current.saving) return
        val after = edit(before)
        if (after == before) return
        state.update { it.copy(preferences = after, saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val saved = save(route.accountId, after)
                state.update { it.copy(preferences = saved, saving = false) }
            } catch (error: CancellationException) {
                state.update { it.copy(preferences = before, saving = false) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(preferences = before, saving = false, saveFailed = true) }
            }
        }
    }
}
