// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/NotificationPreferencesViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationSettings
import run.ostrom.app.domain.models.NotificationSwitch
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.usecases.notifications.LoadNotificationSettingsUseCase
import run.ostrom.app.domain.usecases.notifications.SendTestPushUseCase
import run.ostrom.app.domain.usecases.notifications.SetNotificationSettingUseCase
import run.ostrom.app.presentation.navigation.NotificationPreferencesRoute

data class NotificationSettingsUiState(
    val loading: Boolean = true,
    /** Null until the server answered. */
    val settings: NotificationSettings? = null,
    val canTest: Boolean = false,
    /** The switches being saved: each is shown in its new position and not tappable until the server answered. */
    val saving: Set<NotificationSwitch> = emptySet(),
    val testing: Boolean = false,
    val test: PushTestOutcome? = null,
    val error: Failure? = null,
)

/**
 * The member's switches on one account's server, one per channel and kind, and the test push. A switch flips at once
 * and goes back if the server refuses; the server's answer is what stays shown.
 */
class NotificationSettingsViewModel(
    private val route: NotificationPreferencesRoute,
    private val load: LoadNotificationSettingsUseCase,
    private val set: SetNotificationSettingUseCase,
    private val sendTest: SendTestPushUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = state.asStateFlow()

    init { refresh() }

    fun refresh() {
        // A read on its way back would overwrite a switch being saved.
        if (state.value.saving.isNotEmpty()) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val view = load(route.accountId)
                state.update { it.copy(loading = false, settings = view.settings, canTest = view.canTest) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun toggle(channel: String, kind: NotificationKind, enabled: Boolean) {
        val current = state.value
        val settings = current.settings ?: return
        val switch = NotificationSwitch(channel, kind)
        val before = settings.isOn(channel, kind)
        if (current.loading || switch in current.saving || before == enabled) return
        state.update { it.copy(settings = it.settings?.with(channel, kind, enabled), saving = it.saving + switch, error = null) }
        viewModelScope.launch {
            try {
                val saved = set(route.accountId, channel, kind, enabled)
                state.update { it.copy(settings = saved.keeping(it.saving - switch, it.settings), saving = it.saving - switch) }
            } catch (error: CancellationException) {
                state.update { it.copy(settings = it.settings?.with(channel, kind, before), saving = it.saving - switch) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(settings = it.settings?.with(channel, kind, before), saving = it.saving - switch, error = error.userMessage()) }
            }
        }
    }

    fun test() {
        if (state.value.testing || !state.value.canTest) return
        state.update { it.copy(testing = true, test = null, error = null) }
        viewModelScope.launch {
            try {
                val outcome = sendTest(route.accountId)
                state.update { it.copy(testing = false, test = outcome) }
            } catch (error: CancellationException) {
                state.update { it.copy(testing = false) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(testing = false, error = error.userMessage()) }
            }
        }
    }
}

/** The server's answer, with the switches still being saved kept in the position they were flipped to. */
private fun NotificationSettings.keeping(pending: Set<NotificationSwitch>, shown: NotificationSettings?): NotificationSettings =
    pending.fold(this) { settings, switch -> settings.with(switch.channel, switch.kind, shown?.isOn(switch.channel, switch.kind) ?: true) }
