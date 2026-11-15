// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../presentation/NewSessionViewModelTest.kt (devices, resume); see NOTICE.
package run.ostrom.app.presentation

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.FakeDeviceRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DeviceFolder
import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.ResumableSession
import run.ostrom.app.domain.models.ResumableSessions
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
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
import run.ostrom.app.presentation.viewmodels.NewCloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.SessionTarget
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The new session form on one of the member's online devices: devices, folders, the permission mode, the conversation continued. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewDeviceSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val settings = MapSettings()
    private val onDevices = setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE_DAEMON)
    private val resumes = onDevices + Features.RUNNERS_CLOUD_REMOTE_RESUME
    private val a = ResumableSession("0000000a-aaaa-4bbb-8ccc-00000000000a", "Fix the login bug", 1_000, 4)
    private val b = ResumableSession("0000000b-aaaa-4bbb-8ccc-00000000000b", "Release checklist", 500, 1)

    private fun viewModel(sessions: FakeCloudSessionRepository, features: Set<String>, devices: FakeDeviceRepository): NewCloudSessionViewModel {
        val guard = fakeGuard()
        val access = CloudSessionAccess(sessions, FakeFeatures(features), guard)
        val preferences = SettingsPreferencesRepository(settings)
        return NewCloudSessionViewModel(
            NewCloudSessionRoute("a1"), LoadCloudSessionOptionsUseCase(access), CreateCloudSessionUseCase(access), SequenceIds(),
            DeviceSessionUseCases(LoadHostDevicesUseCase(devices, access), LoadDeviceFoldersUseCase(devices, guard), LoadResumableSessionsUseCase(devices, access),
                CreateDeviceSessionUseCase(access, preferences)),
            preferences,
        )
    }

    private fun sessions() = FakeCloudSessionRepository(cloudSession())

    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    private fun NewCloudSessionViewModel.name(value: String) = onField(CloudField.NAME, value)

    @Test fun aServerWithoutDeviceSessionsOffersTheCloudOnly() = runTest(dispatcher) {
        val devices = FakeDeviceRepository()
        val model = viewModel(sessions(), setOf(Features.RUNNERS_CLOUD), devices)
        settle()
        assertFalse(model.uiState.value.onDevices)
        model.onTarget(SessionTarget.DEVICE); settle()
        assertEquals(SessionTarget.CLOUD, model.uiState.value.target)
        assertTrue(devices.calls.isEmpty())
    }

    @Test fun onlyOnlineDevicesAndTheirPublishedFoldersAreOfferedAndTheFolderNamesTheSession() = runTest(dispatcher) {
        val sessions = sessions()
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1", "Ada's Mac"), hostDevice("d2", "Linux box")) }
            foldersResult = { id ->
                DeviceFolders(true, if (id == "d1") listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "web", "~/git/web")) else listOf(DeviceFolder("f9", "infra", null)))
            }
        }
        val model = viewModel(sessions, onDevices, devices)
        settle()
        assertTrue(model.uiState.value.onDevices)
        assertEquals(SessionTarget.CLOUD, model.uiState.value.target)
        assertTrue(devices.calls.isEmpty())
        model.onTarget(SessionTarget.DEVICE); settle()
        assertEquals(listOf("d1", "d2"), model.uiState.value.devices?.map { it.id })
        // Two devices: none is chosen for the member, so nothing can start yet.
        assertNull(model.uiState.value.deviceId); assertFalse(model.uiState.value.canCreate)
        model.onDevice("d9"); settle()
        assertNull(model.uiState.value.deviceId)
        model.onDevice("d2"); settle()
        // Its only folder is chosen and names the session.
        assertEquals("f9", model.uiState.value.folderId); assertEquals("infra", model.uiState.value.name)
        model.onDevice("d1"); settle()
        assertNull(model.uiState.value.folderId); assertFalse(model.uiState.value.canCreate)
        model.onFolder("f2")
        assertEquals("web", model.uiState.value.name); assertTrue(model.uiState.value.canCreate)
        // A typed name is the member's: another folder no longer changes it.
        model.name("Landing page"); model.onFolder("f1")
        assertEquals("Landing page", model.uiState.value.name)
        model.onDeviceAgent("codex"); model.onField(CloudField.PROMPT, "Ship it")
        model.create(); settle()
        val (sent, _) = sessions.deviceCreations.single()
        assertEquals(NewDeviceSession("Landing page", "d1", "f1", "codex", null, null, PermissionMode.ASK, "Ship it"), sent)
        assertNotNull(model.uiState.value.created)
    }

    @Test fun thePermissionModeOfTheLastLaunchComesBack() = runTest(dispatcher) {
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1")) }
            foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", null))) }
        }
        val first = viewModel(sessions(), onDevices, devices)
        settle()
        first.onTarget(SessionTarget.DEVICE); settle()
        // The only device and its only folder are chosen.
        assertEquals("d1", first.uiState.value.deviceId); assertEquals("f1", first.uiState.value.folderId)
        assertEquals(PermissionMode.ASK, first.uiState.value.permissionMode)
        first.onPermissionMode(PermissionMode.BYPASS)
        first.create(); settle()
        assertNotNull(first.uiState.value.created)
        val next = viewModel(sessions(), onDevices, devices)
        settle()
        assertEquals(PermissionMode.BYPASS, next.uiState.value.permissionMode)
    }

    @Test fun aDeviceStillListedButOfflineForItsFoldersIsDroppedWithoutReadingAgainAndAgain() = runTest(dispatcher) {
        // Its presence lags: the list still names it while its folders answer that it is offline.
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1", "Ada's Mac")) }
            foldersResult = { DeviceFolders(false, emptyList()) }
        }
        val model = viewModel(sessions(), onDevices, devices)
        settle()
        model.onTarget(SessionTarget.DEVICE); settle(); settle()
        assertEquals(listOf("devices", "folders d1"), devices.calls)
        val state = model.uiState.value
        assertEquals(emptyList(), state.devices); assertNull(state.deviceId); assertFalse(state.loadingDevices); assertFalse(state.loadingFolders)
        assertEquals(AppException.DeviceRefused("device_offline").message, state.createError?.text)
        // Refresh reads it again, once.
        model.reloadDevices(); settle()
        assertEquals(listOf("devices", "folders d1", "devices", "folders d1"), devices.calls)
    }

    @Test fun aClearedNameIsTakenFromTheFolderAgainAndAnotherDeviceClearsTheFoldersName() = runTest(dispatcher) {
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1"), hostDevice("d2")) }
            foldersResult = { id -> DeviceFolders(true, listOf(DeviceFolder("f-$id", "app-$id", null), DeviceFolder("g-$id", "web-$id", null))) }
        }
        val model = viewModel(sessions(), onDevices, devices)
        settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1"); settle()
        model.onFolder("f-d1")
        assertEquals("app-d1", model.uiState.value.name)
        model.onDevice("d2"); settle()
        // No folder chosen on the other device: the first device's folder no longer names the session.
        assertEquals("", model.uiState.value.name)
        model.name("Mine"); model.name("")
        model.onFolder("g-d2")
        assertEquals("web-d2", model.uiState.value.name)
    }

    @Test fun aDeviceGoneOfflineIsRefusedSaidAndDroppedAndARetryKeepsItsKey() = runTest(dispatcher) {
        val sessions = sessions()
        var online = listOf(hostDevice("d1", "Ada's Mac"))
        val devices = FakeDeviceRepository().apply {
            devicesResult = { online }
            foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", null))) }
        }
        val model = viewModel(sessions, onDevices, devices)
        settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        assertTrue(model.uiState.value.canCreate)
        // A network failure: the retry sends the same key, so the server answers what it may have created.
        sessions.createOnDeviceResult = { throw AppException.Unreachable() }
        model.create(); settle()
        model.create(); settle()
        val keys = sessions.deviceCreations.map { it.second }
        assertEquals(2, keys.size); assertEquals(keys[0], keys[1])
        // Nothing is edited while a creation is in flight, so a retry sends what was sent, under its key.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        sessions.createOnDeviceResult = { gate.await(); throw AppException.Unreachable() }
        model.create(); runCurrent()
        assertTrue(model.uiState.value.creating)
        model.onField(CloudField.PROMPT, "Changed meanwhile"); model.onField(CloudField.NAME, "Other")
        assertEquals("app", model.uiState.value.name); assertEquals("", model.uiState.value.prompt)
        gate.complete(Unit); settle()
        assertEquals(3, sessions.deviceCreations.size); assertEquals(sessions.deviceCreations[0], sessions.deviceCreations.last())
        model.create(); settle()
        assertEquals(sessions.deviceCreations[0], sessions.deviceCreations.last())
        // What is sent changes: another key.
        model.onField(CloudField.EFFORT, "high")
        model.create(); settle()
        assertEquals(5, sessions.deviceCreations.size); assertEquals(2, sessions.deviceCreations.map { it.second }.distinct().size)
        // The device went offline meanwhile: refused, said in words, and no longer offered.
        online = emptyList()
        sessions.createOnDeviceResult = { throw AppException.Server(409, "device_offline") }
        model.create(); settle()
        val state = model.uiState.value
        assertEquals(AppException.DeviceRefused("device_offline").message, state.createError?.text)
        assertEquals(emptyList(), state.devices); assertNull(state.deviceId); assertFalse(state.canCreate)
        assertNull(state.created)
    }

    private fun resumingDevices(daemonResumes: Boolean = true) = FakeDeviceRepository().apply {
        devicesResult = { listOf(hostDevice("d1", "Ada's Mac"), hostDevice("d2", "Linux box")) }
        foldersResult = { id ->
            DeviceFolders(true, if (id == "d1") listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "web", "~/git/web")) else listOf(DeviceFolder("f9", "infra", null)),
                daemonResumes)
        }
        resumableResult = { _, folder, before ->
            if (folder == "f1" && before == null) ResumableSessions(listOf(a), nextBefore = "c1") else if (folder == "f1") ResumableSessions(listOf(b)) else ResumableSessions(emptyList())
        }
    }

    @Test fun nothingIsChosenByDefaultTheListIsReadWhenOpenedAndTheChoiceIsSent() = runTest(dispatcher) {
        val sessions = sessions()
        val devices = resumingDevices()
        val model = viewModel(sessions, resumes, devices)
        settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        model.onDevice("d1"); settle()
        assertFalse(model.uiState.value.showsResume, "Not before a folder is chosen")
        model.onFolder("f1")
        assertTrue(model.uiState.value.showsResume)
        assertNull(model.uiState.value.resumeId, "None by default")
        assertTrue(devices.calls.none { it.startsWith("resumable") }, "Nothing is read on the device until the picker opens")
        model.onResumeOpen(); settle()
        assertEquals(listOf(a), model.uiState.value.resumable)
        assertEquals("resumable d1 f1 20 before=null", devices.calls.last())
        model.onResumeMore(); settle()
        assertEquals(listOf(a, b), model.uiState.value.resumable, "The older page goes under the first")
        model.onResume("not-listed")
        assertNull(model.uiState.value.resumeId)
        model.onResume(a.id)
        assertEquals(a.id, model.uiState.value.resumeId); assertFalse(model.uiState.value.resumeOpen)
        model.create(); settle()
        assertEquals(a.id, sessions.deviceCreations.single().first.resumeSessionId)
    }

    @Test fun anotherFolderDeviceOrAgentClearsTheChoice() = runTest(dispatcher) {
        val sessions = sessions()
        val model = viewModel(sessions, resumes, resumingDevices())
        settle()
        model.onTarget(SessionTarget.DEVICE); settle()
        fun choose() { model.onFolder("f1"); model.onResumeOpen(); settle(); model.onResume(a.id) }
        model.onDevice("d1"); settle()
        choose()
        model.onFolder("f2")
        assertNull(model.uiState.value.resumeId); assertNull(model.uiState.value.resumable, "The list belongs to its folder")
        choose()
        model.onDevice("d2"); settle()
        assertNull(model.uiState.value.resumeId); assertNull(model.uiState.value.resumable)
        model.onDevice("d1"); settle()
        choose()
        model.onDeviceAgent("codex")
        assertNull(model.uiState.value.resumeId); assertFalse(model.uiState.value.showsResume, "Claude Code only")
        model.onDeviceAgent("claude")
        assertNull(model.uiState.value.resumeId, "Not brought back")
        model.onResume(a.id)
        model.onResume(null)
        model.create(); settle()
        assertNull(sessions.deviceCreations.single().first.resumeSessionId)
    }

    @Test fun anOlderDaemonOrServerHidesOrExplainsThePickerAndAFailureIsShownInIt() = runTest(dispatcher) {
        val old = viewModel(sessions(), resumes, resumingDevices(daemonResumes = false))
        settle(); old.onTarget(SessionTarget.DEVICE); settle(); old.onDevice("d1"); settle(); old.onFolder("f1")
        assertTrue(old.uiState.value.showsResume); assertFalse(old.uiState.value.deviceResumes)
        old.onResumeOpen(); settle()
        assertNull(old.uiState.value.resumable, "Nothing is asked of a daemon that cannot answer")

        val olderServer = viewModel(sessions(), onDevices, resumingDevices())
        settle(); olderServer.onTarget(SessionTarget.DEVICE); settle(); olderServer.onDevice("d1"); settle(); olderServer.onFolder("f1")
        assertFalse(olderServer.uiState.value.showsResume)

        val failing = resumingDevices().apply { resumableResult = { _, _, _ -> throw AppException.Server(504, "device_no_answer") } }
        val model = viewModel(sessions(), resumes, failing)
        settle(); model.onTarget(SessionTarget.DEVICE); settle(); model.onDevice("d1"); settle(); model.onFolder("f1")
        model.onResumeOpen(); settle()
        assertEquals(AppException.DeviceRefused("device_no_answer").message, model.uiState.value.resumableError?.text)
        assertNull(model.uiState.value.createError, "The form itself can still start a new conversation")
        assertTrue(model.uiState.value.canCreate)
        failing.resumableResult = { _, _, _ -> ResumableSessions(listOf(a)) }
        model.onResumeRetry(); settle()
        assertEquals(listOf(a), model.uiState.value.resumable); assertNull(model.uiState.value.resumableError)
    }
}
