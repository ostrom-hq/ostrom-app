// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/SessionViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.presentation.navigation.CloudSessionRoute

/** An action on a cloud session. */
enum class CloudAction { PAUSE, RESUME, STOP }

data class CloudSessionUiState(
    val session: CloudSession? = null,
    val loading: Boolean = true,
    val gone: Boolean = false,
    val error: Failure? = null,
    /** The action under way; null when none. */
    val acting: CloudAction? = null,
    /** The failure of the last action, apart from the page's. */
    val actionError: Failure? = null,
    /** The server lets the person choose the session's GitHub access (`runners.cloud.github.v1`). */
    val githubAccessOffered: Boolean = false,
    /** The GitHub access being set; null when none. */
    val settingAccess: GitHubAccess? = null,
) {
    /** The choice shows on a session whose access the server says, while it is not over. */
    val canChooseAccess: Boolean get() = githubAccessOffered && session?.let { it.githubAccess != null && !it.status.ended } == true
}

/**
 * One cloud session's record with Pause, Resume and Stop (and the choice of its GitHub access, where the server offers it): read on open, on return to the screen and on [load]; each
 * action takes the server's answer, then reads the session again (the server carries it out on its own pace).
 */
class CloudSessionViewModel(
    private val route: CloudSessionRoute,
    private val getSession: GetCloudSessionUseCase,
    private val control: ControlCloudSessionUseCase,
    private val options: LoadCloudSessionOptionsUseCase,
    private val setGitHubAccess: SetCloudSessionGitHubAccessUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(CloudSessionUiState())
    val uiState: StateFlow<CloudSessionUiState> = state.asStateFlow()
    private var loading: Job? = null

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val session = getSession(route.accountId, route.sessionId)
                val offered = options(route.accountId).githubAccess
                state.update { it.copy(session = session, loading = false, gone = false, githubAccessOffered = offered) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppException.NotFound) {
                state.update { it.copy(loading = false, gone = true, error = error.userMessage()) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun pause() = act(CloudAction.PAUSE)
    fun resume() = act(CloudAction.RESUME)

    /** Stops the session for good (the screen confirms first). */
    fun stop() = act(CloudAction.STOP)

    /** Chooses whose GitHub access the session uses; a refusal is worded by its key, then the session is read again. */
    fun chooseGitHubAccess(choice: GitHubAccess) {
        val session = state.value.session ?: return
        if (!state.value.canChooseAccess || state.value.acting != null || state.value.settingAccess != null || session.githubAccess == choice) return
        state.update { it.copy(settingAccess = choice, actionError = null) }
        viewModelScope.launch {
            try {
                val changed = setGitHubAccess(route.accountId, session, choice)
                state.update { it.copy(settingAccess = null, session = changed) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(settingAccess = null, actionError = error.userMessage()) }
            }
            load()
        }
    }

    private fun act(action: CloudAction) {
        val session = state.value.session ?: return
        if (state.value.acting != null) return
        state.update { it.copy(acting = action, actionError = null) }
        viewModelScope.launch {
            try {
                val changed = when (action) {
                    CloudAction.PAUSE -> control.pause(route.accountId, session)
                    CloudAction.RESUME -> control.resume(route.accountId, session)
                    CloudAction.STOP -> control.stop(route.accountId, session)
                }
                state.update { it.copy(acting = null, session = changed) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(acting = null, actionError = error.userMessage()) }
            }
            // Either way, read it again: what the server did meanwhile shows.
            load()
        }
    }
}
