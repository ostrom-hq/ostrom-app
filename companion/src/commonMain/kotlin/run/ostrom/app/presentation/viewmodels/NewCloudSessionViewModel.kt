// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/NewSessionViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.usecases.sessions.CloudSessionRules
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.presentation.navigation.NewCloudSessionRoute

/** A field of the form, for [NewCloudSessionViewModel.onField]. */
enum class CloudField { NAME, MODEL, PROMPT, REPO, REF, WORKING_DIRECTORY, SETUP_CREATE, SETUP_START, VARIABLES, EMAIL_DOMAIN }

/** A field of a recipe's repository row. */
enum class RepositoryField { URL, PATH, REF, BRANCH }

data class NewCloudSessionUiState(
    val loading: Boolean = true,
    val error: Failure? = null,
    /** The server takes a preparation recipe (`runners.cloud.environment.v1`); otherwise the single repository and ref. */
    val recipeServed: Boolean = false,
    val agent: CloudAgent = CloudAgent.CLAUDE,
    val name: String = "",
    val model: String = "",
    val prompt: String = "",
    val repo: String = "",
    val ref: String = "",
    val repositories: List<CloudRepository> = listOf(CloudRepository("")),
    val workingDirectory: String = "",
    /** One command per line. */
    val setupCreate: String = "",
    val setupStart: String = "",
    /** Names separated by commas, spaces or lines. */
    val variables: String = "",
    val requireGitHub: Boolean = false,
    val emailDomain: String = "",
    val creating: Boolean = false,
    val createError: Failure? = null,
    val created: CloudSession? = null,
) {
    val canCreate: Boolean get() = !loading && error == null && !creating && created == null
    val canAddRepository: Boolean get() = repositories.size < CloudSessionRules.MAX_REPOSITORIES

    /** What is sent: the recipe on a server that takes one, the single repository otherwise. */
    fun form(): NewCloudSession = NewCloudSession(
        agent, name = name, model = model, prompt = prompt,
        repo = repo.takeIf { !recipeServed }, ref = ref.takeIf { !recipeServed },
        recipe = if (!recipeServed) null else CloudRecipe(
            repositories = repositories,
            workingDirectory = workingDirectory,
            setupCreate = setupCreate.lines(),
            setupStart = setupStart.lines(),
            requiredVariables = variables.split(',', ' ', '\n', '\t'),
            requireGitHub = requireGitHub,
            gitEmailDomain = emailDomain,
        ),
    )
}

/** The new cloud session form: what the server takes ([load]), the fields, and Start, which lands on the new session. */
class NewCloudSessionViewModel(
    private val route: NewCloudSessionRoute,
    private val loadOptions: LoadCloudSessionOptionsUseCase,
    private val createSession: CreateCloudSessionUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(NewCloudSessionUiState())
    val uiState: StateFlow<NewCloudSessionUiState> = state.asStateFlow()

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val options = loadOptions(route.accountId)
                state.update { it.copy(loading = false, recipeServed = options.recipe) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun onAgent(agent: CloudAgent) = state.update { it.copy(agent = agent, createError = null) }

    fun onField(field: CloudField, value: String) = state.update {
        when (field) {
            CloudField.NAME -> it.copy(name = value.take(CloudSessionRules.MAX_NAME))
            CloudField.MODEL -> it.copy(model = value)
            CloudField.PROMPT -> it.copy(prompt = value.take(CloudSessionRules.MAX_PROMPT))
            CloudField.REPO -> it.copy(repo = value)
            CloudField.REF -> it.copy(ref = value)
            CloudField.WORKING_DIRECTORY -> it.copy(workingDirectory = value)
            CloudField.SETUP_CREATE -> it.copy(setupCreate = value)
            CloudField.SETUP_START -> it.copy(setupStart = value)
            CloudField.VARIABLES -> it.copy(variables = value)
            CloudField.EMAIL_DOMAIN -> it.copy(emailDomain = value)
        }.copy(createError = null)
    }

    fun onRequireGitHub(required: Boolean) = state.update { it.copy(requireGitHub = required, createError = null) }

    fun onRepository(index: Int, field: RepositoryField, value: String) = state.update { current ->
        current.copy(
            repositories = current.repositories.mapIndexed { i, repository ->
                if (i != index) repository else when (field) {
                    RepositoryField.URL -> repository.copy(url = value)
                    RepositoryField.PATH -> repository.copy(path = value)
                    RepositoryField.REF -> repository.copy(ref = value)
                    RepositoryField.BRANCH -> repository.copy(branch = value)
                }
            },
            createError = null,
        )
    }

    fun addRepository() = state.update { if (it.canAddRepository) it.copy(repositories = it.repositories + CloudRepository("")) else it }

    fun removeRepository(index: Int) = state.update { current ->
        current.copy(repositories = current.repositories.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(CloudRepository("")) })
    }

    fun create() {
        val current = state.value
        if (!current.canCreate) return
        state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                val session = createSession(route.accountId, current.form())
                state.update { it.copy(creating = false, created = session) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(creating = false, createError = error.userMessage()) }
            }
        }
    }
}
