// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/NewSessionViewModel.kt; see NOTICE.
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
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.DeviceFolder
import run.ostrom.app.domain.models.HostDevice
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.ResumableSession
import run.ostrom.app.domain.repositories.PreferencesRepository
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.usecases.sessions.CloudSessionRules
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.CreateDeviceSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadDeviceFoldersUseCase
import run.ostrom.app.domain.usecases.sessions.LoadHostDevicesUseCase
import run.ostrom.app.domain.usecases.sessions.LoadResumableSessionsUseCase
import run.ostrom.app.presentation.navigation.NewCloudSessionRoute

/** A field of the form, for [NewCloudSessionViewModel.onField]. */
enum class CloudField { NAME, MODEL, EFFORT, PROMPT, REPO, REF, WORKING_DIRECTORY, SETUP_CREATE, SETUP_START, VARIABLES, EMAIL_DOMAIN }

/** Where the new session runs: a pod the server starts, or a folder of one of the member's online devices. */
enum class SessionTarget { CLOUD, DEVICE }

/** A field of a recipe's repository row. */
enum class RepositoryField { URL, PATH, REF, BRANCH }

data class NewCloudSessionUiState(
    val loading: Boolean = true,
    val error: Failure? = null,
    /** The server takes a preparation recipe (`runners.cloud.environment.v1`); otherwise the single repository and ref. */
    val recipeServed: Boolean = false,
    /** The server runs conversation sessions (`runners.cloud.conversation.v1`): claude and codex may start as one. */
    val conversationServed: Boolean = false,
    /** Start the agent headless, talked to from this app, instead of in a terminal. */
    val conversation: Boolean = false,
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
    /** Why the session did not start, or why the devices, folders or conversations could not be read. */
    val createError: Failure? = null,
    val created: CloudSession? = null,
    // ---- On one of the member's devices (`runners.cloud.remote.daemon.v1`), TeamCode's NewSessionUiState ----------
    /** The server starts sessions on the member's online devices: the form offers both targets. */
    val onDevices: Boolean = false,
    val target: SessionTarget = SessionTarget.CLOUD,
    /** The member's devices whose daemon is online; null until they were read. */
    val devices: List<HostDevice>? = null,
    val loadingDevices: Boolean = false,
    val deviceId: String? = null,
    /** The chosen device's folders; null until they were read. */
    val folders: List<DeviceFolder>? = null,
    val loadingFolders: Boolean = false,
    val folderId: String? = null,
    /** The server lets a session continue a conversation of the folder (`runners.cloud.remote.resume.v1`). */
    val resumes: Boolean = false,
    /** The chosen device's daemon lists the folder's conversations; false for an older Ostrom there. */
    val deviceResumes: Boolean = false,
    /** The folder's conversations, read when the picker first opens; null until then. */
    val resumable: List<ResumableSession>? = null,
    /** The cursor of the next, older page; null on the last one. */
    val resumableNext: String? = null,
    val loadingResumable: Boolean = false,
    /** Why the conversations could not be read, shown in the picker; the form can still start a new conversation. */
    val resumableError: Failure? = null,
    val resumeOpen: Boolean = false,
    /** The conversation the session continues; null, the default, starts a new one. */
    val resumeId: String? = null,
    /** The agent on a device: `claude` or `codex`. */
    val deviceAgent: String = "claude",
    /** The last launch's choice, until changed. */
    val permissionMode: PermissionMode = PermissionMode.ASK,
    /** False until the member types a name: on a device, the folder's name follows the chosen folder. */
    val nameEdited: Boolean = false,
    val effort: String = "",
) {
    /** The picker shows on a device, once a folder is chosen, for Claude Code only. */
    val showsResume: Boolean get() = resumeFrom != null

    /** The devices listed without [id]; nothing listed stays nothing listed. */
    internal fun devicesWithout(id: String): List<HostDevice>? = devices?.filter { it.id != id }

    /**
     * A page of the folder's conversations: the newest one ([before] null) replaces the list, an older one is added under
     * it without repeating one shown.
     */
    internal fun withResumable(before: String?, sessions: List<ResumableSession>, next: String?): NewCloudSessionUiState {
        val shown = if (before == null) sessions else {
            val known = resumable.orEmpty()
            known + sessions.filter { s -> known.none { k -> k.id == s.id } }
        }
        return copy(resumable = shown, resumableNext = next, loadingResumable = false)
    }

    /** The device and folder whose conversations the picker lists, while it shows; a folder never comes without its device. */
    internal val resumeFrom: Pair<String, String>? get() =
        if (target == SessionTarget.DEVICE && onDevices && resumes && deviceAgent == "claude") deviceId?.let { device -> folderId?.let { device to it } } else null

    // Not while something loads: its answer can change what is sent, and so the key a retry must reuse.
    val canCreate: Boolean get() = !loading && error == null && !creating && created == null && when (target) {
        SessionTarget.CLOUD -> true
        SessionTarget.DEVICE -> onDevices && !loadingDevices && !loadingFolders && deviceId != null && folderId != null && name.isNotBlank()
    }
    val canAddRepository: Boolean get() = repositories.size < CloudSessionRules.MAX_REPOSITORIES

    /** The conversation choice shows for an agent the server runs headless. */
    val conversationOffered: Boolean get() = conversationServed && agent in CloudSessionRules.CONVERSATION_AGENTS

    /** What is sent: the recipe on a server that takes one, the single repository otherwise. */
    fun form(): NewCloudSession = NewCloudSession(
        agent, name = name, model = model, prompt = prompt,
        mode = if (conversation && conversationOffered) CloudSessionMode.CONVERSATION else CloudSessionMode.TERMINAL,
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

    /** What is sent on a device: always a conversation, the permission mode, the conversation continued when offered. */
    fun deviceForm(): NewDeviceSession = NewDeviceSession(
        name, deviceId.orEmpty(), folderId.orEmpty(), deviceAgent, model, effort, permissionMode, prompt,
        resumeSessionId = resumeId.takeIf { showsResume },
    ).normalized()

    /** The form as sent, for the idempotency key: the device form, or the cloud one. */
    internal fun sent(): Any = when (target) {
        SessionTarget.CLOUD -> form()
        SessionTarget.DEVICE -> deviceForm()
    }
}

/**
 * The new session form, in the cloud or on one of the member's online devices: what the server takes ([load]), the
 * fields, and Start, which lands on the new session.
 */
class NewCloudSessionViewModel(
    private val route: NewCloudSessionRoute,
    private val loadOptions: LoadCloudSessionOptionsUseCase,
    private val createSession: CreateCloudSessionUseCase,
    private val ids: IdGenerator,
    private val devices: DeviceSessionUseCases? = null,
    preferences: PreferencesRepository? = null,
) : ViewModel() {
    private val state = MutableStateFlow(NewCloudSessionUiState(permissionMode = if (preferences != null) preferences.permissionMode else PermissionMode.ASK))
    val uiState: StateFlow<NewCloudSessionUiState> = state.asStateFlow()

    /**
     * One key per form as it was pressed: Start pressed again on the same form, after a lost answer, returns the
     * session the first press started (TeamCode's and the console's key per press); any change of what is sent starts
     * a new key (the server refuses a replayed key with another body).
     */
    private var key = ids.newId()
    private var keyedForm: Any? = null
    private var devicesJob: Job? = null
    private var foldersJob: Job? = null
    private var resumableJob: Job? = null

    init { load() }

    fun load() {
        // A creation in flight keeps its form and key: what it sent is what a retry sends again.
        if (state.value.creating) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val options = loadOptions(route.accountId)
                val onDevices = options.onDevices && devices != null
                state.update {
                    it.copy(loading = false, recipeServed = options.recipe, conversationServed = options.conversation, onDevices = onDevices,
                        resumes = options.resumes, target = if (onDevices) it.target else SessionTarget.CLOUD)
                }
                if (state.value.target == SessionTarget.DEVICE) reloadDevices()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun onTarget(target: SessionTarget) {
        if (target == SessionTarget.DEVICE && !state.value.onDevices) return
        edit { it.copy(target = target) }
        // Read again at each switch: a device listed earlier may have gone offline since.
        if (target == SessionTarget.DEVICE && !state.value.loadingDevices) reloadDevices()
    }

    /**
     * Reads the online devices again (one may have come online); a device gone offline is dropped with its folder.
     * [keepError]: after a refusal, which stays shown.
     */
    fun reloadDevices() = reloadDevices(keepError = false)

    private fun reloadDevices(keepError: Boolean) {
        val uses = devices ?: return
        if (state.value.creating) return
        foldersJob?.cancel()
        devicesJob?.cancel()
        change { it.copy(loadingDevices = true, loadingFolders = false, createError = if (keepError) it.createError else null) }
        devicesJob = viewModelScope.launch {
            try {
                val found = uses.loadDevices(route.accountId)
                val kept = state.value.deviceId?.takeIf { id -> found.any { it.id == id } } ?: found.singleOrNull()?.id
                change {
                    it.copy(devices = found, loadingDevices = false, deviceId = kept, folders = if (kept == it.deviceId) it.folders else null,
                        folderId = if (kept == it.deviceId) it.folderId else null)
                }
                kept?.let { loadFoldersOf(uses, it, keepError) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.userMessage()
                change { it.copy(loadingDevices = false, createError = message) }
            }
        }
    }

    fun onDevice(id: String) {
        val uses = devices ?: return
        if (state.value.creating || state.value.devices?.none { it.id == id } != false) return
        // The same device again only reads its folders again after they failed.
        if (id == state.value.deviceId && state.value.folders != null && state.value.createError == null) return
        edit { it.copy(deviceId = id, folders = null, deviceResumes = false).withFolder(null) }
        loadFoldersOf(uses, id)
    }

    /** The folders [deviceId] offers now; one folder alone is chosen. Only the answer for the device still chosen counts. */
    private fun loadFoldersOf(uses: DeviceSessionUseCases, deviceId: String, keepError: Boolean = false) {
        foldersJob?.cancel()
        change { it.copy(loadingFolders = true, createError = if (keepError) it.createError else null) }
        foldersJob = viewModelScope.launch {
            try {
                val found = uses.loadFolders.detailed(route.accountId, deviceId)
                val folders = found.folders
                if (state.value.deviceId != deviceId) return@launch
                val kept = state.value.folderId?.takeIf { id -> folders.any { it.id == id } } ?: folders.singleOrNull()?.id
                change { it.copy(folders = folders, loadingFolders = false, deviceResumes = found.resumes).withFolder(kept) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.deviceId != deviceId) return@launch
                // The device went offline or is gone: the list no longer offers it. Dropped here without reading the list
                // again, which could still name it (its presence lags) and lead back here.
                val message = error.userMessage()
                if (error.isDeviceGone()) change {
                    it.copy(devices = it.devicesWithout(deviceId), deviceId = null, folders = null, loadingFolders = false, createError = message)
                        .withFolder(null)
                } else change { it.copy(loadingFolders = false, folders = it.folders ?: emptyList(), createError = message) }
            }
        }
    }

    fun onFolder(id: String) {
        val folders = state.value.folders ?: return
        if (folders.none { it.id == id }) return
        edit { it.withFolder(id) }
    }

    // Codex continues no Claude Code conversation: the choice goes with the agent.
    fun onDeviceAgent(value: String) = edit { if (value == it.deviceAgent) it else it.copy(deviceAgent = value).withoutResume() }

    /** Opens or closes the picker; the first opening reads the folder's conversations on the device. */
    fun onResumeOpen() {
        val uses = devices ?: return
        val current = state.value
        val from = current.resumeFrom ?: return
        if (current.creating || !current.deviceResumes) return
        change { it.copy(resumeOpen = !it.resumeOpen) }
        if (state.value.resumeOpen && current.resumable == null && !current.loadingResumable) loadResumableOf(uses, from, null)
    }

    /** Reads the conversations again after a failure. */
    fun onResumeRetry() {
        val uses = devices ?: return
        val from = state.value.resumeFrom ?: return
        if (state.value.deviceResumes && !state.value.loadingResumable) loadResumableOf(uses, from, null)
    }

    /** The next, older page, added under the ones shown, while the picker shows. */
    fun onResumeMore() {
        val uses = devices ?: return
        val from = state.value.resumeFrom ?: return
        state.value.resumableNext?.let { if (!state.value.loadingResumable) loadResumableOf(uses, from, it) }
    }

    /** [id] null: none, a new conversation. Closes the picker. */
    fun onResume(id: String?) {
        if (id != null && state.value.resumable?.none { it.id == id } != false) return
        edit { it.copy(resumeId = id, resumeOpen = false) }
    }

    /** Only the answer for the device and folder still chosen counts. */
    private fun loadResumableOf(uses: DeviceSessionUseCases, from: Pair<String, String>, before: String?) {
        val (deviceId, folderId) = from
        resumableJob?.cancel()
        change { it.copy(loadingResumable = true, resumableError = null) }
        resumableJob = viewModelScope.launch {
            try {
                val page = uses.loadResumable(route.accountId, deviceId, folderId, before)
                if (state.value.deviceId != deviceId || state.value.folderId != folderId) return@launch
                change { it.withResumable(before, page.sessions, page.nextBefore) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.deviceId != deviceId || state.value.folderId != folderId) return@launch
                val message = error.userMessage()
                change { it.copy(loadingResumable = false, resumableError = message) }
            }
        }
    }

    fun onPermissionMode(mode: PermissionMode) = edit { it.copy(permissionMode = mode) }

    private fun edit(update: (NewCloudSessionUiState) -> NewCloudSessionUiState) {
        if (state.value.creating) return
        change { update(it).copy(createError = null) }
    }

    /** Applies [update]; another device or folder cancels the conversations read for the previous one. */
    private fun change(update: (NewCloudSessionUiState) -> NewCloudSessionUiState) {
        val before = state.value
        val after = update(before)
        if (after.deviceId != before.deviceId || after.folderId != before.folderId) resumableJob?.cancel()
        state.value = after
    }

    private fun NewCloudSessionUiState.withFolder(id: String?): NewCloudSessionUiState {
        val folder = folders?.firstOrNull { it.id == id }
        // Unless the member named it, the session takes the folder's name, and none without a folder. A conversation
        // belongs to its folder: another folder (or device, which goes through here) clears the choice and the list.
        val moved = folder?.id != folderId
        return copy(folderId = folder?.id, name = if (nameEdited) name else folder?.name.orEmpty()).let { if (moved) it.withoutResume(list = true) else it }
    }

    private fun NewCloudSessionUiState.withoutResume(list: Boolean = false): NewCloudSessionUiState = copy(resumeId = null, resumeOpen = false,
        resumable = if (list) null else resumable, resumableNext = if (list) null else resumableNext,
        loadingResumable = if (list) false else loadingResumable, resumableError = if (list) null else resumableError)

    private fun Exception.isDeviceGone() =
        this is AppException.DeviceRefused && (code == AppException.DeviceRefused.DEVICE_OFFLINE || code == DEVICE_NOT_FOUND)

    // Nothing is edited while a creation is in flight: what was sent keeps its key for a retry.
    fun onAgent(agent: CloudAgent) = state.update { if (it.creating) it else it.copy(agent = agent, createError = null) }

    fun onField(field: CloudField, value: String) = state.update {
        if (it.creating) return@update it
        when (field) {
            // A name cleared lets the folder name the session again.
            CloudField.NAME -> it.copy(name = value.take(CloudSessionRules.MAX_NAME), nameEdited = value.isNotBlank())
            CloudField.MODEL -> it.copy(model = value)
            CloudField.EFFORT -> it.copy(effort = value)
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

    fun onConversation(conversation: Boolean) = state.update { it.copy(conversation = conversation, createError = null) }

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
        val sent = current.sent()
        if (keyedForm != null && sent != keyedForm) key = ids.newId()
        keyedForm = sent
        val pressKey = key
        // The device use cases when the target is a device (load() offers that target only with them); null for the cloud.
        val device = devices.takeIf { current.target == SessionTarget.DEVICE }
        state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                val session = if (device != null) device.create(route.accountId, current.deviceForm(), pressKey)
                    else createSession(route.accountId, current.form(), pressKey)
                state.update { it.copy(creating = false, created = session) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(creating = false, createError = error.userMessage()) }
                if (device != null) when {
                    error.isDeviceGone() -> reloadDevices(keepError = true)
                    // The device of the form as sent (canCreate needs one).
                    error is AppException.DeviceRefused && error.code == FOLDER_NOT_PUBLISHED -> loadFoldersOf(device, current.deviceForm().deviceId, keepError = true)
                }
            }
        }
    }

    private companion object {
        const val FOLDER_NOT_PUBLISHED = "folder_not_published"
        const val DEVICE_NOT_FOUND = "device_not_found"
    }
}

/** What the form uses to start a session on one of the member's devices (`runners.cloud.remote.daemon.v1`). */
class DeviceSessionUseCases(
    val loadDevices: LoadHostDevicesUseCase,
    val loadFolders: LoadDeviceFoldersUseCase,
    val loadResumable: LoadResumableSessionsUseCase,
    val create: CreateDeviceSessionUseCase,
)
