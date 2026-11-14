// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/UpdateBannerViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.repositories.UpdatePreferences
import run.ostrom.app.domain.services.CliUpdateException
import run.ostrom.app.domain.services.CliUpdater
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.usecases.updates.UpdatePolicy

/** What the update banner shows about the `ostrom` CLI. */
sealed interface UpdateBannerState {
    data object Hidden : UpdateBannerState

    /** [version] of the CLI is available; [installed] is the one on this computer. */
    data class Available(val version: String, val installed: String) : UpdateBannerState

    /** `ostrom update` runs; [output] holds its last lines. */
    data class Updating(val version: String, val output: List<String> = emptyList()) : UpdateBannerState

    /** The CLI now reports [version]. */
    data class Updated(val version: String) : UpdateBannerState

    /** The update failed; "Try again" offers [version] again. [lines] are the CLI's last lines, shown as they are. */
    data class Failed(val version: String, val reason: CliUpdateException.Reason, val lines: List<String> = emptyList()) : UpdateBannerState
}

/**
 * The update banner: a check at startup, then every [UpdatePolicy.CHECK_INTERVAL_MILLIS], never more than once per
 * [UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS] across restarts (the last result is kept meanwhile). A failed check is
 * silent. A version the person closed does not show again; a newer one does.
 */
class UpdateBannerViewModel(
    private val updater: CliUpdater,
    private val preferences: UpdatePreferences,
    private val clock: Clock,
) : ViewModel() {
    private val state = MutableStateFlow<UpdateBannerState>(UpdateBannerState.Hidden)
    val uiState: StateFlow<UpdateBannerState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) delay(refresh())
        }
    }

    /**
     * One check: `ostrom update --check` when due, the last result otherwise; silent on failure. Returns how long to
     * wait before the next one: [UpdatePolicy.CHECK_INTERVAL_MILLIS] after a check that worked, an hour after one that
     * failed (offline at startup), the rest of the hour otherwise.
     */
    internal suspend fun refresh(): Long {
        val now = clock.nowMillis()
        val wait = UpdatePolicy.nextCheckDelay(now, preferences.lastAttemptMillis)
        var next = wait
        val latest = if (wait > 0) preferences.latestVersion else {
            preferences.lastAttemptMillis = now
            try {
                updater.latestVersion().also { preferences.latestVersion = it; next = UpdatePolicy.CHECK_INTERVAL_MILLIS }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                next = UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS
                preferences.latestVersion
            }
        }
        val installed = try { updater.installedVersion() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        val target = UpdatePolicy.target(installed, latest)?.takeIf { it != preferences.dismissedVersion }
        state.update { current ->
            when (current) {
                // An update in progress, its result or its failure stays on screen until the person acts on it.
                is UpdateBannerState.Updating, is UpdateBannerState.Updated, is UpdateBannerState.Failed -> current
                else -> if (target != null && installed != null) UpdateBannerState.Available(target, installed) else UpdateBannerState.Hidden
            }
        }
        return next
    }

    /** Runs `ostrom update`, its lines shown as they come. */
    fun update() {
        val version = when (val current = state.value) {
            is UpdateBannerState.Available -> current.version
            // Still running: a second `ostrom update` would race the first; the next check shows the banner again.
            is UpdateBannerState.Failed -> current.version.takeIf { current.reason != CliUpdateException.Reason.STILL_RUNNING } ?: return
            else -> return
        }
        state.value = UpdateBannerState.Updating(version)
        viewModelScope.launch {
            try {
                val installed = updater.update { line ->
                    state.update { if (it is UpdateBannerState.Updating) it.copy(output = (it.output + line).takeLast(MAX_OUTPUT_LINES)) else it }
                }
                state.value = installed?.let { UpdateBannerState.Updated(it) }
                    ?: UpdateBannerState.Failed(version, CliUpdateException.Reason.NOT_INSTALLED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val output = (state.value as? UpdateBannerState.Updating)?.output.orEmpty()
                val failure = error as? CliUpdateException
                state.value = UpdateBannerState.Failed(
                    version,
                    failure?.reason ?: CliUpdateException.Reason.FAILED,
                    failure?.lines?.takeIf { it.isNotEmpty() } ?: output,
                )
            }
        }
    }

    /**
     * "Not now": the banner of this version does not show again, a newer one does. Closing a result or a failure only
     * hides it: a version that failed to install is offered again at the next check.
     */
    fun dismiss() {
        when (val current = state.value) {
            is UpdateBannerState.Available -> preferences.dismissedVersion = current.version
            is UpdateBannerState.Failed, is UpdateBannerState.Updated -> Unit
            else -> return
        }
        state.value = UpdateBannerState.Hidden
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 6
    }
}
