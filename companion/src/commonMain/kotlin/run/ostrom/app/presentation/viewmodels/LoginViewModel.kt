// SPDX-License-Identifier: AGPL-3.0-only
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
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.usecases.auth.BrowserSignInUseCase
import run.ostrom.app.domain.usecases.auth.DeviceSignInUseCase
import run.ostrom.app.domain.usecases.auth.InspectServerUseCase
import run.ostrom.app.presentation.navigation.LoginRoute

data class LoginUiState(
    val origin: String,
    /** The server as it answered just now; null while it is being read. */
    val server: ServerInfo? = null,
    val busy: Boolean = false,
    /** The device flow's code and page, on a computer, while the person enters it. */
    val deviceLogin: DeviceLogin? = null,
    val error: Failure? = null,
    /** The account that signed in: the screen leaves for the app. */
    val signedIn: Account? = null,
)

/**
 * GitHub sign-in on one server: with the system's browser on a phone ([browser] is bound there), with the device flow
 * of `ostrom login` on a computer (the code shown, the page opened, the server asked until the person said yes).
 */
class LoginViewModel(
    route: LoginRoute,
    private val inspect: InspectServerUseCase,
    private val browser: BrowserSignInUseCase?,
    private val device: DeviceSignInUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(LoginUiState(origin = route.origin))
    val uiState: StateFlow<LoginUiState> = state.asStateFlow()
    private var attempt: Job? = null

    /** Whether this platform signs in with the browser (a phone) rather than a code (a computer). */
    val usesBrowser: Boolean get() = browser != null

    init {
        loadServer()
    }

    /** Reads the server again (it could not be reached). */
    fun loadServer() {
        state.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                val server = inspect(state.value.origin)
                state.update { it.copy(server = server) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(error = error.userMessage()) }
            }
        }
    }

    fun signIn() {
        val server = state.value.server ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, deviceLogin = null) }
        attempt = viewModelScope.launch {
            try {
                // A browser closed before the end of the sign-in is no failure: the screen is back as it was.
                val account = if (browser != null) browser(server) else {
                    val login = device.start(server)
                    state.update { it.copy(deviceLogin = login) }
                    device.await(login)
                }
                state.update { it.copy(busy = false, deviceLogin = null, signedIn = account) }
            } catch (error: CancellationException) {
                state.update { it.copy(busy = false, deviceLogin = null) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(busy = false, deviceLogin = null, error = error.userMessage()) }
            }
        }
    }

    /** Stops waiting for the device flow's code; the server forgets it when it expires. */
    fun cancel() {
        attempt?.cancel()
        attempt = null
    }
}
