// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/viewmodels/McpServersViewModel.kt (Apache-2.0); see NOTICE.
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
import run.ostrom.app.demo.isDemo
import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.usecases.setup.LoadMemberEnvironmentUseCase
import run.ostrom.app.domain.usecases.setup.LoadPersonalMcpServersUseCase
import run.ostrom.app.presentation.navigation.MemberEnvironmentRoute
import run.ostrom.app.presentation.navigation.PersonalMcpServersRoute

/**
 * A read-only screen of what the member set up for their own agents: [content] once read (kept while a refresh runs or
 * fails), [error] for the last failure, and [consoleUrl], the console page where they change it (a write there needs a
 * fresh browser sign-in, which the app does not have); null for the demo or a signed-out account.
 */
data class MemberSetupUiState<T>(
    val loading: Boolean = true,
    val content: T? = null,
    val error: Failure? = null,
    val consoleUrl: String? = null,
)

/** Loads one account's [MemberSetupUiState] on creation and on [refresh]; a newer refresh replaces the older one. */
abstract class MemberSetupViewModel<T>(
    private val accountId: String,
    accounts: AccountRepository,
    consolePath: String,
) : ViewModel() {
    private val state = MutableStateFlow(
        MemberSetupUiState<T>(consoleUrl = accounts.find(accountId)?.takeIf { it.signedIn && !it.isDemo }?.let { "${it.origin}$consolePath" }),
    )
    val uiState: StateFlow<MemberSetupUiState<T>> = state.asStateFlow()
    private var job: Job? = null

    /** Reads the content of [accountId]. */
    protected abstract suspend fun read(accountId: String): T

    /** Called by the subclass's init, once its loader is set. */
    protected fun start() = refresh()

    fun refresh() {
        job?.cancel()
        state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val content = read(accountId)
                state.update { it.copy(loading = false, content = content) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }
}

/** The member's environment: the names they receive, those withheld, and their personal variants, never a value. */
class MemberEnvironmentViewModel(
    route: MemberEnvironmentRoute,
    accounts: AccountRepository,
    private val load: LoadMemberEnvironmentUseCase,
) : MemberSetupViewModel<MemberEnvironment>(route.accountId, accounts, "/me/environment") {
    init { start() }

    override suspend fun read(accountId: String): MemberEnvironment = load(accountId)
}

/** The member's personal MCP servers, with their process state. */
class PersonalMcpServersViewModel(
    route: PersonalMcpServersRoute,
    accounts: AccountRepository,
    private val load: LoadPersonalMcpServersUseCase,
) : MemberSetupViewModel<List<PersonalMcpServer>>(route.accountId, accounts, "/me/mcp") {
    init { start() }

    override suspend fun read(accountId: String): List<PersonalMcpServer> = load(accountId)
}
