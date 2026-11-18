// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.FakeDeviceRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.DeviceFolder
import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.HostDevice
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.ResumableSession
import run.ostrom.app.domain.models.ResumableSessions
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.CloudSessionRules
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.CreateDeviceSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadDeviceFoldersUseCase
import run.ostrom.app.domain.usecases.sessions.LoadHostDevicesUseCase
import run.ostrom.app.domain.usecases.sessions.LoadResumableSessionsUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.hostDevice
import run.ostrom.app.infrastructure.settings.SettingsPreferencesRepository
import run.ostrom.app.presentation.navigation.NewCloudSessionRoute
import run.ostrom.app.presentation.viewmodels.CloudField
import run.ostrom.app.presentation.viewmodels.DeviceSessionUseCases
import run.ostrom.app.presentation.viewmodels.Failure
import run.ostrom.app.presentation.viewmodels.NewCloudSessionUiState
import run.ostrom.app.presentation.viewmodels.NewCloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.RepositoryField
import run.ostrom.app.presentation.viewmodels.SessionTarget
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the new session form's other tests leave out: the derived flags, the guards, the failures and the races of the device flow. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewCloudSessionViewModelCoverageTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val settings = MapSettings()
    private val onDevices = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE_DAEMON)
    private val resumes = onDevices + Features.RUNNERS_CLOUD_REMOTE_RESUME
    private val served = FakeFeatures(resumes)
    private val sessions = FakeCloudSessionRepository(cloudSession())
    private val devices = FakeDeviceRepository()
    private val a = ResumableSession("0000000a-aaaa-4bbb-8ccc-00000000000a", "Fix the login bug", 1_000, 4)
    private val b = ResumableSession("0000000b-aaaa-4bbb-8ccc-00000000000b", "Release checklist", 500, 1)

    private fun viewModel(withDevices: Boolean = true, withPreferences: Boolean = true): NewCloudSessionViewModel {
        val guard = fakeGuard()
        val access = CloudSessionAccess(sessions, served, guard)
        val preferences = SettingsPreferencesRepository(settings)
        return NewCloudSessionViewModel(
            NewCloudSessionRoute("a1"), LoadCloudSessionOptionsUseCase(access), CreateCloudSessionUseCase(access), SequenceIds(),
            if (withDevices) {
                DeviceSessionUseCases(
                    LoadHostDevicesUseCase(devices, access), LoadDeviceFoldersUseCase(devices, guard), LoadResumableSessionsUseCase(devices, access),
                    CreateDeviceSessionUseCase(access, preferences),
                )
            } else null,
            if (withPreferences) preferences else null,
        )
    }

    private fun TestScope.settle() = runCurrent()

    private val NewCloudSessionViewModel.st get() = uiState.value

    /** Two online devices: d1 with the folders f1 and f2 (f1 has conversations), d2 with f9. */
    private fun twoDevices(daemonResumes: Boolean = true) {
        devices.devicesResult = { listOf(hostDevice("d1"), hostDevice("d2")) }
        devices.foldersResult = { id ->
            DeviceFolders(
                true,
                if (id == "d1") listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "web", "~/git/web")) else listOf(DeviceFolder("f9", "infra", null)),
                daemonResumes,
            )
        }
        devices.resumableResult = { _, folder, before ->
            if (folder == "f1" && before == null) ResumableSessions(listOf(a), nextBefore = "c1") else if (folder == "f1") ResumableSessions(listOf(a, b)) else ResumableSessions(emptyList())
        }
    }

    /** One online device, d1, with the one folder f1. */
    private fun oneDevice() {
        devices.devicesResult = { listOf(hostDevice("d1")) }
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", null)), true) }
    }

    private fun TestScope.chooseFolder(model: NewCloudSessionViewModel) {
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1"); settle()
        model.onFolder("f1")
    }

    private fun reads(prefix: String) = devices.calls.count { it.startsWith(prefix) }

    // ---- The derived flags, on states built directly ------------------------------------------------------------

    @Test fun thePickerShowsOnlyWhenEveryConditionHolds() {
        val base = NewCloudSessionUiState(target = SessionTarget.DEVICE, onDevices = true, resumes = true, folderId = "f1", deviceAgent = "claude")
        assertTrue(base.showsResume)
        assertFalse(base.copy(target = SessionTarget.CLOUD).showsResume)
        assertFalse(base.copy(onDevices = false).showsResume)
        assertFalse(base.copy(resumes = false).showsResume)
        assertFalse(base.copy(folderId = null).showsResume)
        assertFalse(base.copy(deviceAgent = "codex").showsResume)
    }

    @Test fun aSessionStartsOnlyWhenNothingLoadsOrFailsAndTheTargetHasWhatItNeeds() {
        val cloud = NewCloudSessionUiState(loading = false)
        assertTrue(cloud.canCreate)
        assertFalse(cloud.copy(loading = true).canCreate)
        assertFalse(cloud.copy(error = Failure("down")).canCreate)
        assertFalse(cloud.copy(creating = true).canCreate)
        assertFalse(cloud.copy(created = cloudSession()).canCreate)
        val device = cloud.copy(target = SessionTarget.DEVICE, onDevices = true, deviceId = "d1", folderId = "f1", name = "app")
        assertTrue(device.canCreate)
        assertFalse(device.copy(onDevices = false).canCreate)
        assertFalse(device.copy(loadingDevices = true).canCreate)
        assertFalse(device.copy(loadingFolders = true).canCreate)
        assertFalse(device.copy(deviceId = null).canCreate)
        assertFalse(device.copy(folderId = null).canCreate)
        assertFalse(device.copy(name = "  ").canCreate)
    }

    @Test fun aDeviceFormWithoutADeviceOrFolderSendsEmptyIds() {
        val form = NewCloudSessionUiState(name = "app").deviceForm()
        assertEquals("", form.deviceId)
        assertEquals("", form.folderId)
    }

    // ---- Loading -------------------------------------------------------------------------------------------------

    @Test fun aServerThatRefusesShowsTheErrorAndALaterLoadRecovers() = runTest(dispatcher) {
        served.features = emptySet()
        val model = viewModel(); settle()
        assertNotNull(model.st.error)
        assertFalse(model.st.loading); assertFalse(model.st.canCreate)
        served.features = onDevices
        model.load(); settle()
        assertNull(model.st.error)
        assertTrue(model.st.onDevices)
    }

    @Test fun withoutDeviceUseCasesOnlyTheCloudIsOfferedAndNoDeviceActionDoesAnything() = runTest(dispatcher) {
        val model = viewModel(withDevices = false, withPreferences = false); settle()
        assertFalse(model.st.onDevices, "the server offers devices but this form cannot use them")
        assertEquals(PermissionMode.ASK, model.st.permissionMode)
        val before = model.st
        model.onTarget(SessionTarget.DEVICE); model.reloadDevices(); model.onDevice("d1"); model.onFolder("f1")
        model.onResumeOpen(); model.onResumeRetry(); model.onResumeMore(); settle()
        assertEquals(before, model.st)
        assertTrue(devices.calls.isEmpty())
    }

    @Test fun loadingAgainOnTheDeviceTargetReadsTheDevicesAgain() = runTest(dispatcher) {
        twoDevices()
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        assertEquals(1, reads("devices"))
        model.load(); settle()
        assertEquals(2, reads("devices"))
        assertEquals(SessionTarget.DEVICE, model.st.target)
        assertFalse(model.st.loading)
    }

    // ---- Devices -------------------------------------------------------------------------------------------------

    @Test fun theDevicesAreReadOnceWhileAReadIsInFlightAndAFailureIsShown() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        devices.devicesResult = { gate.await(); listOf(hostDevice("d1")) }
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", null))) }
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.CLOUD); settle()
        assertTrue(devices.calls.isEmpty(), "the cloud reads no device")
        model.onTarget(SessionTarget.DEVICE); settle()
        assertTrue(model.st.loadingDevices)
        model.onTarget(SessionTarget.DEVICE); settle()
        assertEquals(1, reads("devices"))
        // Nothing to choose before the devices and folders are read.
        model.onDevice("d1"); model.onFolder("f1")
        assertNull(model.st.deviceId); assertNull(model.st.folderId)
        gate.complete(Unit); settle()
        assertEquals("d1", model.st.deviceId, "the only device is chosen")
        assertEquals("f1", model.st.folderId)
        devices.devicesResult = { throw AppException.Unreachable() }
        model.reloadDevices(); settle()
        assertEquals("The server could not be reached.", model.st.createError?.text)
        assertFalse(model.st.loadingDevices)
    }

    @Test fun aReadAgainKeepsTheChosenDeviceAndFolderOrFallsBackToTheOnlyOne() = runTest(dispatcher) {
        twoDevices()
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        assertNull(model.st.deviceId, "two devices: none is chosen for the member")
        model.onDevice("d1"); settle()
        model.onFolder("f1")
        model.reloadDevices(); settle()
        assertEquals("d1", model.st.deviceId); assertEquals("f1", model.st.folderId); assertEquals("app", model.st.name)
        // The folder is gone but another stands alone: it is taken, and names the session.
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("g1", "api", null)), true) }
        model.reloadDevices(); settle()
        assertEquals("g1", model.st.folderId); assertEquals("api", model.st.name)
        // d1 went offline: the other device is the only one left.
        devices.devicesResult = { listOf(hostDevice("d2")) }
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f9", "infra", null))) }
        model.reloadDevices(); settle()
        assertEquals("d2", model.st.deviceId); assertEquals("f9", model.st.folderId)
        // None left.
        devices.devicesResult = { emptyList() }
        model.reloadDevices(); settle()
        assertNull(model.st.deviceId); assertNull(model.st.folderId)
        assertEquals(emptyList(), model.st.devices)
        // Two again: none chosen.
        twoDevices()
        model.reloadDevices(); settle()
        assertNull(model.st.deviceId)
        assertEquals(listOf("d1", "d2"), model.st.devices?.map(HostDevice::id))
    }

    @Test fun aFolderOrThePickerIsNotTakenBeforeTheFoldersAreRead() = runTest(dispatcher) {
        twoDevices()
        val model = viewModel(); settle()
        // Still on the cloud target: there is no picker to open.
        model.onResumeOpen(); settle()
        assertFalse(model.st.resumeOpen)
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1")
        // The folders are on their way: a folder cannot be taken yet, and the same device asks again.
        model.onFolder("f1")
        assertNull(model.st.folderId)
        val asked = reads("folders")
        model.onDevice("d1"); settle()
        assertEquals(asked + 1, reads("folders"))
        // A device chosen but no folder: nothing to resume from.
        model.onFolder("f2")
        model.onResumeOpen(); settle()
        assertTrue(model.st.resumeOpen)
    }

    @Test fun foldersThatCannotBeReadShowTheErrorAndTheSameDeviceReadsThemAgain() = runTest(dispatcher) {
        twoDevices()
        devices.foldersResult = { throw AppException.Unreachable() }
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1"); settle()
        assertEquals("The server could not be reached.", model.st.createError?.text)
        assertEquals(emptyList(), model.st.folders)
        assertFalse(model.st.loadingFolders)
        // The same device while its folders failed: read again.
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", null)), true) }
        model.onDevice("d1"); settle()
        assertEquals("f1", model.st.folderId); assertNull(model.st.createError)
        // The same device with its folders read and nothing wrong: nothing is asked.
        val asked = devices.calls.size
        model.onDevice("d1"); settle()
        assertEquals(asked, devices.calls.size)
        // A refresh whose folders fail keeps the ones shown.
        devices.foldersResult = { throw AppException.Unreachable() }
        model.reloadDevices(); settle()
        assertEquals(listOf("f1"), model.st.folders?.map { it.id })
        assertNotNull(model.st.createError)
    }

    @Test fun aDeviceThatIsNotTheMembersAnyMoreIsDropped() = runTest(dispatcher) {
        oneDevice()
        devices.foldersResult = { throw AppException.DeviceRefused("device_not_found") }
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        assertEquals(emptyList(), model.st.devices)
        assertNull(model.st.deviceId)
        assertEquals(AppException.DeviceRefused("device_not_found").message, model.st.createError?.text)
        assertEquals(1, reads("devices"), "the list is not read again to say so")
    }

    @Test fun theFoldersOfADeviceNoLongerChosenAreIgnored() = runTest(dispatcher) {
        twoDevices()
        val gate = CompletableDeferred<Unit>()
        devices.foldersResult = { id ->
            if (id == "d1") {
                withContext(NonCancellable) { gate.await() }
                DeviceFolders(true, listOf(DeviceFolder("f1", "app", null)))
            } else DeviceFolders(true, listOf(DeviceFolder("f9", "infra", null)))
        }
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1"); settle()
        model.onDevice("d2"); settle()
        assertEquals("f9", model.st.folderId)
        gate.complete(Unit); settle()
        assertEquals("d2", model.st.deviceId); assertEquals("f9", model.st.folderId)
        assertEquals(listOf("f9"), model.st.folders?.map { it.id })
    }

    @Test fun aFailureOfTheFoldersOfADeviceNoLongerChosenIsIgnored() = runTest(dispatcher) {
        twoDevices()
        val gate = CompletableDeferred<Unit>()
        devices.foldersResult = { id ->
            if (id == "d1") {
                withContext(NonCancellable) { gate.await() }
                throw AppException.DeviceRefused("device_offline")
            } else DeviceFolders(true, listOf(DeviceFolder("f9", "infra", null)))
        }
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1"); settle()
        model.onDevice("d2"); settle()
        gate.complete(Unit); settle()
        assertEquals("d2", model.st.deviceId)
        assertNull(model.st.createError)
        assertEquals(listOf("d1", "d2"), model.st.devices?.map { it.id }, "d1 stays listed: its failure was for a choice no longer made")
    }

    // ---- Nothing changes while a session is being created --------------------------------------------------------

    @Test fun nothingIsEditedReadOrStartedAgainWhileASessionIsBeingCreated() = runTest(dispatcher) {
        twoDevices()
        val gate = CompletableDeferred<Unit>()
        sessions.createOnDeviceResult = { gate.await(); sessions.session }
        val model = viewModel(); settle()
        chooseFolder(model)
        model.create(); settle()
        assertTrue(model.st.creating)
        val frozen = model.st
        val devicesRead = reads("devices")
        model.load(); model.reloadDevices(); model.onDevice("d2"); model.onTarget(SessionTarget.CLOUD); model.onFolder("f2")
        model.onDeviceAgent("codex"); model.onPermissionMode(PermissionMode.BYPASS); model.onResume(null); model.onResumeOpen()
        model.onAgent(CloudAgent.CODEX); model.onField(CloudField.PROMPT, "changed"); model.create(); settle()
        assertEquals(frozen, model.st)
        assertEquals(devicesRead, reads("devices"))
        assertEquals(1, sessions.deviceCreations.size)
        gate.complete(Unit); settle()
        assertNotNull(model.st.created)
        model.create(); settle()
        assertEquals(1, sessions.deviceCreations.size, "started once")
    }

    @Test fun nothingStartsWithoutAChosenFolder() = runTest(dispatcher) {
        twoDevices()
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        model.create(); settle()
        assertTrue(sessions.deviceCreations.isEmpty())
        assertFalse(model.st.creating)
    }

    // ---- The conversation picker ---------------------------------------------------------------------------------

    @Test fun thePickerOpensClosesAndReopensWithoutReadingTwiceAndTheNextPageSkipsWhatIsShown() = runTest(dispatcher) {
        twoDevices()
        val model = viewModel(); settle()
        chooseFolder(model)
        model.onResumeOpen(); settle()
        assertTrue(model.st.resumeOpen)
        assertEquals(listOf(a), model.st.resumable)
        assertEquals(1, reads("resumable"))
        model.onResumeOpen(); settle()
        assertFalse(model.st.resumeOpen)
        model.onResumeOpen(); settle()
        assertTrue(model.st.resumeOpen)
        assertEquals(1, reads("resumable"), "the list is already there")
        model.onResumeMore(); settle()
        assertEquals(listOf(a, b), model.st.resumable, "the page repeats a conversation already shown: it is not added twice")
        assertNull(model.st.resumableNext)
        model.onResumeMore(); settle()
        assertEquals(2, reads("resumable"), "no older page to read")
    }

    @Test fun nothingIsReadAgainWhileTheConversationsAreLoadingAndTheRetryNeedsAPickerThatCanRead() = runTest(dispatcher) {
        twoDevices()
        val gate = CompletableDeferred<Unit>()
        devices.resumableResult = { _, _, before -> if (before == null) ResumableSessions(listOf(a), "c1") else { gate.await(); ResumableSessions(listOf(b)) } }
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1"); settle()
        model.onResumeRetry(); model.onResume("x"); settle()
        assertEquals(0, reads("resumable"), "no folder chosen: no picker")
        model.onFolder("f1")
        model.onResume(a.id)
        assertNull(model.st.resumeId, "nothing listed yet")
        model.onResumeOpen(); settle()
        model.onResumeMore(); settle()
        assertTrue(model.st.loadingResumable)
        model.onResumeMore(); model.onResumeRetry(); settle()
        assertEquals(2, reads("resumable"), "the page in flight is the only one asked for")
        gate.complete(Unit); settle()
        assertEquals(listOf(a, b), model.st.resumable)
        // Closed and reopened while a read is in flight does not read twice either.
        val reopened = CompletableDeferred<Unit>()
        devices.resumableResult = { _, _, _ -> reopened.await(); ResumableSessions(listOf(a)) }
        model.onFolder("f2"); model.onFolder("f1")
        model.onResumeOpen(); settle()
        assertTrue(model.st.loadingResumable)
        model.onResumeOpen(); model.onResumeOpen(); settle()
        assertEquals(3, reads("resumable"))
    }

    @Test fun aDaemonThatCannotListConversationsIsNotAskedToRetry() = runTest(dispatcher) {
        twoDevices(daemonResumes = false)
        val model = viewModel(); settle()
        chooseFolder(model)
        assertTrue(model.st.showsResume); assertFalse(model.st.deviceResumes)
        model.onResumeRetry(); settle()
        assertEquals(0, reads("resumable"))
    }

    @Test fun theSameAgentChosenAgainKeepsTheConversation() = runTest(dispatcher) {
        twoDevices()
        val model = viewModel(); settle()
        chooseFolder(model)
        model.onResumeOpen(); settle()
        model.onResume(a.id)
        model.onDeviceAgent("claude")
        assertEquals(a.id, model.st.resumeId)
    }

    @Test fun aConversationListForAnotherFolderOrDeviceIsIgnored() = runTest(dispatcher) {
        twoDevices()
        val first = CompletableDeferred<Unit>()
        devices.resumableResult = { _, _, _ -> withContext(NonCancellable) { first.await() }; ResumableSessions(listOf(a)) }
        val model = viewModel(); settle()
        chooseFolder(model)
        model.onResumeOpen(); settle()
        assertTrue(model.st.loadingResumable)
        model.onFolder("f2")
        first.complete(Unit); settle()
        assertNull(model.st.resumable)
        assertFalse(model.st.loadingResumable)
        val second = CompletableDeferred<Unit>()
        devices.resumableResult = { _, _, _ -> withContext(NonCancellable) { second.await() }; ResumableSessions(listOf(a)) }
        model.onFolder("f1")
        model.onResumeOpen(); settle()
        model.onDevice("d2"); settle()
        second.complete(Unit); settle()
        assertEquals("d2", model.st.deviceId)
        assertNull(model.st.resumable)
    }

    @Test fun aFailureOfAConversationListForAnotherFolderOrDeviceIsIgnored() = runTest(dispatcher) {
        twoDevices()
        val first = CompletableDeferred<Unit>()
        devices.resumableResult = { _, _, _ -> withContext(NonCancellable) { first.await() }; throw AppException.Unreachable() }
        val model = viewModel(); settle()
        chooseFolder(model)
        model.onResumeOpen(); settle()
        model.onFolder("f2")
        first.complete(Unit); settle()
        assertNull(model.st.resumableError)
        val second = CompletableDeferred<Unit>()
        devices.resumableResult = { _, _, _ -> withContext(NonCancellable) { second.await() }; throw AppException.Unreachable() }
        model.onFolder("f1")
        model.onResumeOpen(); settle()
        model.onDevice("d2"); settle()
        second.complete(Unit); settle()
        assertNull(model.st.resumableError)
    }

    // ---- Starting on a device ------------------------------------------------------------------------------------

    @Test fun aDeviceNotFoundAtCreationIsSaidDroppedAndKeepsItsSentence() = runTest(dispatcher) {
        oneDevice()
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        assertTrue(model.st.canCreate)
        devices.devicesResult = { emptyList() }
        sessions.createOnDeviceResult = { throw AppException.Server(404, "device_not_found") }
        model.create(); settle()
        assertEquals(AppException.DeviceRefused("device_not_found").message, model.st.createError?.text)
        assertEquals(emptyList(), model.st.devices)
        assertNull(model.st.deviceId)
        assertEquals(2, reads("devices"))
    }

    @Test fun aFolderNoLongerPublishedReadsTheFoldersAgainAndKeepsItsSentence() = runTest(dispatcher) {
        oneDevice()
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        val folders = reads("folders")
        sessions.createOnDeviceResult = { throw AppException.Server(409, "folder_not_published") }
        model.create(); settle()
        assertEquals(AppException.DeviceRefused("folder_not_published").message, model.st.createError?.text)
        assertEquals(folders + 1, reads("folders"))
        assertEquals("f1", model.st.folderId)
    }

    @Test fun anotherRefusalOfTheDeviceReadsNothingAgain() = runTest(dispatcher) {
        oneDevice()
        val model = viewModel(); settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        val before = devices.calls.size
        sessions.createOnDeviceResult = { throw AppException.Server(400, "invalid_folder") }
        model.create(); settle()
        assertEquals(AppException.DeviceRefused("invalid_folder").message, model.st.createError?.text)
        assertEquals(before, devices.calls.size)
        assertFalse(model.st.creating)
    }

    // ---- The cloud form ------------------------------------------------------------------------------------------

    @Test fun everyFieldOfTheFormIsKept() = runTest(dispatcher) {
        served.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_ENVIRONMENT)
        val model = viewModel(withDevices = false); settle()
        assertTrue(model.st.recipeServed)
        model.onField(CloudField.NAME, "n".repeat(CloudSessionRules.MAX_NAME + 5))
        assertEquals(CloudSessionRules.MAX_NAME, model.st.name.length); assertTrue(model.st.nameEdited)
        model.onField(CloudField.NAME, "  ")
        assertFalse(model.st.nameEdited, "a name cleared lets the folder name the session again")
        model.onField(CloudField.MODEL, "opus")
        model.onField(CloudField.EFFORT, "high")
        model.onField(CloudField.PROMPT, "p".repeat(CloudSessionRules.MAX_PROMPT + 5))
        model.onField(CloudField.REPO, "https://github.com/acme/api")
        model.onField(CloudField.REF, "main")
        model.onField(CloudField.WORKING_DIRECTORY, "app")
        model.onField(CloudField.SETUP_CREATE, "npm ci")
        model.onField(CloudField.SETUP_START, "npm start")
        model.onField(CloudField.VARIABLES, "TOKEN")
        model.onField(CloudField.EMAIL_DOMAIN, "acme.dev")
        model.onRequireGitHub(true)
        model.onConversation(true)
        val state = model.st
        assertEquals("opus", state.model); assertEquals("high", state.effort); assertEquals(CloudSessionRules.MAX_PROMPT, state.prompt.length)
        assertEquals("https://github.com/acme/api", state.repo); assertEquals("main", state.ref); assertEquals("app", state.workingDirectory)
        assertEquals("npm ci", state.setupCreate); assertEquals("npm start", state.setupStart); assertEquals("TOKEN", state.variables)
        assertEquals("acme.dev", state.emailDomain); assertTrue(state.requireGitHub); assertTrue(state.conversation)
        val recipe = state.form().recipe
        assertEquals("app", recipe?.workingDirectory); assertEquals("acme.dev", recipe?.gitEmailDomain); assertEquals(true, recipe?.requireGitHub)
        assertNull(state.form().repo, "the recipe replaces the single repository")
    }

    @Test fun theRepositoriesOfARecipeAreEditedAddedAndRemoved() = runTest(dispatcher) {
        served.features = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_ENVIRONMENT)
        val model = viewModel(withDevices = false); settle()
        model.addRepository()
        assertEquals(2, model.st.repositories.size)
        model.onRepository(1, RepositoryField.URL, "https://github.com/acme/api")
        model.onRepository(1, RepositoryField.PATH, "api")
        model.onRepository(1, RepositoryField.REF, "v1")
        model.onRepository(1, RepositoryField.BRANCH, "work")
        assertEquals(CloudRepository("https://github.com/acme/api", "api", "v1", "work"), model.st.repositories[1])
        assertEquals(CloudRepository(""), model.st.repositories[0], "the other rows are untouched")
        repeat(CloudSessionRules.MAX_REPOSITORIES + 4) { model.addRepository() }
        assertEquals(CloudSessionRules.MAX_REPOSITORIES, model.st.repositories.size)
        assertFalse(model.st.canAddRepository)
        model.removeRepository(99)
        assertEquals(CloudSessionRules.MAX_REPOSITORIES, model.st.repositories.size, "no row has that index")
        model.removeRepository(1)
        assertEquals(CloudSessionRules.MAX_REPOSITORIES - 1, model.st.repositories.size)
        assertTrue(model.st.canAddRepository)
        repeat(CloudSessionRules.MAX_REPOSITORIES + 4) { model.removeRepository(0) }
        assertEquals(listOf(CloudRepository("")), model.st.repositories, "one empty row always stays")
    }
}
