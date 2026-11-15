// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/DeviceSessionUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeDeviceRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.DeviceFolder
import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.ResumableSession
import run.ostrom.app.domain.models.ResumableSessions
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.CreateDeviceSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadDeviceFoldersUseCase
import run.ostrom.app.domain.usecases.sessions.LoadHostDevicesUseCase
import run.ostrom.app.domain.usecases.sessions.LoadResumableSessionsUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.hostDevice
import run.ostrom.app.infrastructure.settings.SettingsPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sessions started on one of the member's online devices: which devices, which folders, what is sent and kept. */
class DeviceSessionUseCasesTest {
    private val guard = fakeGuard()
    private val devices = FakeDeviceRepository()
    private val sessions = FakeCloudSessionRepository(cloudSession())
    private val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_REMOTE_DAEMON))
    private val settings = MapSettings()
    private val preferences = SettingsPreferencesRepository(settings)
    private val form = NewDeviceSession(" Fix ", "d1", "f1", "claude", " ", null, PermissionMode.BYPASS, "  ")

    private fun access(features: FakeFeatures = this.features) = CloudSessionAccess(sessions, features, guard)

    @Test fun aServerWithoutDeviceSessionsOffersNoDevice() = runTest {
        val older = FakeFeatures(setOf(Features.RUNNERS_CLOUD))
        val load = LoadHostDevicesUseCase(devices, access(older))
        assertEquals(Features.RUNNERS_CLOUD_REMOTE_DAEMON, assertFailsWith<AppException.Unsupported> { load("a1") }.feature)
        assertTrue(devices.calls.isEmpty())
        val create = CreateDeviceSessionUseCase(access(older), preferences)
        assertFailsWith<AppException.Unsupported> { create("a1", form, "k") }
        assertTrue(sessions.deviceCreations.isEmpty())
    }

    @Test fun aFoldersConversationsAreReadOnlyFromAServerThatListsThemAndTheirRefusalsAreWorded() = runTest {
        val id = "0000000a-aaaa-4bbb-8ccc-00000000000a"
        devices.resumableResult = { _, _, _ -> ResumableSessions(listOf(ResumableSession(id, "Fix the build", 1_000, 2))) }
        assertEquals(Features.RUNNERS_CLOUD_REMOTE_RESUME,
            assertFailsWith<AppException.Unsupported> { LoadResumableSessionsUseCase(devices, access())("a1", "d1", "f1") }.feature)
        assertTrue(devices.calls.isEmpty(), "An older server is never asked")
        val resumes = FakeFeatures(features.features + Features.RUNNERS_CLOUD_REMOTE_RESUME)
        val load = LoadResumableSessionsUseCase(devices, access(resumes))
        assertEquals(listOf(id), load("a1", "d1", "f1", before = "c9").sessions.map { it.id })
        assertEquals("resumable d1 f1 ${LoadResumableSessionsUseCase.PAGE} before=c9", devices.calls.single())
        for (code in listOf("daemon_resume_unsupported", "device_no_answer", "device_offline", "folder_missing")) {
            devices.resumableResult = { _, _, _ -> throw AppException.Server(409, code) }
            val refused = assertFailsWith<AppException.DeviceRefused> { load("a1", "d1", "f1") }
            assertEquals(code, refused.code); assertFalse(refused.message!!.contains("HTTP"))
        }
        // The choice goes with the form, Claude Code only, and only to a server that continues conversations.
        assertFailsWith<AppException.Unsupported> { CreateDeviceSessionUseCase(access(), preferences)("a1", form.copy(resumeSessionId = id), "k") }
        assertTrue(sessions.deviceCreations.isEmpty())
        val create = CreateDeviceSessionUseCase(access(resumes), preferences)
        create("a1", form.copy(resumeSessionId = id), "k1")
        create("a1", form.copy(agent = "codex", resumeSessionId = id), "k2")
        assertEquals(listOf(id, null), sessions.deviceCreations.map { it.first.resumeSessionId })
    }

    @Test fun thisDeviceComesFirstThenTheOthersByName() = runTest {
        devices.devicesResult = { listOf(hostDevice("d3", "zeta"), hostDevice("d1", "Alpha"), hostDevice("d2", "mine", current = true)) }
        assertEquals(listOf("d2", "d1", "d3"), LoadHostDevicesUseCase(devices, access())("a1").map { it.id })
    }

    @Test fun aDeviceThatWentOfflineIsRefusedWithItsFoldersAndAnotherMembersIsWorded() = runTest {
        val load = LoadDeviceFoldersUseCase(devices, guard)
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f2", "web", "~/git/web"), DeviceFolder("f1", "app", "~/git/app"))) }
        assertEquals(listOf("f1", "f2"), load("a1", "d1").map { it.id })
        devices.foldersResult = { DeviceFolders(false, listOf(DeviceFolder("f1", "app", null))) }
        assertEquals("device_offline", assertFailsWith<AppException.DeviceRefused> { load("a1", "d1") }.code)
        devices.foldersResult = { throw AppException.Server(404, "device_not_found") }
        val gone = assertFailsWith<AppException.DeviceRefused> { load("a1", "d9") }
        assertEquals("device_not_found", gone.code); assertFalse(gone.message!!.contains("HTTP"))
        devices.foldersResult = { throw AppException.Server(500, "internal") }
        assertFailsWith<AppException.Server> { load("a1", "d1") }
    }

    @Test fun theSessionIsSentTrimmedAndThePermissionModeKeptForTheNextLaunch() = runTest {
        assertEquals(PermissionMode.ASK, preferences.permissionMode)
        val create = CreateDeviceSessionUseCase(access(), preferences)
        create("a1", form, "key-1")
        val (sent, key) = sessions.deviceCreations.single()
        assertEquals(NewDeviceSession("Fix", "d1", "f1", "claude", null, null, PermissionMode.BYPASS, null), sent)
        assertEquals("key-1", key)
        // Kept across a restart of the app.
        assertEquals(PermissionMode.BYPASS, SettingsPreferencesRepository(settings).permissionMode)
    }

    @Test fun theServersRefusalsAboutTheDeviceAreWordedAndTheOthersKept() = runTest {
        val create = CreateDeviceSessionUseCase(access(), preferences)
        for (code in listOf("device_offline", "folder_not_published", "device_not_found", "remote_requires_login")) {
            sessions.createOnDeviceResult = { throw AppException.Server(409, code) }
            val refused = assertFailsWith<AppException.DeviceRefused> { create("a1", form, "k") }
            assertEquals(code, refused.code); assertFalse(refused.message!!.contains("HTTP"), refused.message)
        }
        sessions.createOnDeviceResult = { throw AppException.Server(500, "internal") }
        assertFailsWith<AppException.Server> { create("a1", form, "k") }
        // No session started with Bypass: the next launch does not start from it.
        assertEquals(PermissionMode.ASK, preferences.permissionMode)
        assertEquals("The server refused to start the session on this device (odd_code).", AppException.DeviceRefused("odd_code").message)
    }

    @Test fun aFormWithoutDeviceFolderOrKnownAgentIsNeverSent() = runTest {
        val create = CreateDeviceSessionUseCase(access(), preferences)
        assertEquals(CloudSessionProblem.CHOOSE_DEVICE, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(deviceId = ""), "k") }.problem)
        assertEquals(CloudSessionProblem.CHOOSE_FOLDER, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(folderId = " "), "k") }.problem)
        assertEquals(CloudSessionProblem.CHOOSE_AGENT, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(agent = "gemini"), "k") }.problem)
        assertEquals(CloudSessionProblem.NAME, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(name = "  "), "k") }.problem)
        assertEquals(CloudSessionProblem.PROMPT, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(initialMessage = "x".repeat(20_001)), "k") }.problem)
        assertEquals(CloudSessionProblem.MODEL, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(model = "opus 4"), "k") }.problem)
        assertEquals(CloudSessionProblem.MODEL, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(model = "m".repeat(121)), "k") }.problem)
        assertEquals(CloudSessionProblem.EFFORT, assertFailsWith<AppException.CloudSessionInvalid> { create("a1", form.copy(effort = "very high"), "k") }.problem)
        assertTrue(sessions.deviceCreations.isEmpty())
        // A refused form keeps the last launch's mode.
        assertEquals(PermissionMode.ASK, preferences.permissionMode)
    }
}
