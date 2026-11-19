// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.NotificationSettings
import run.ostrom.app.domain.models.NotificationSwitch
import run.ostrom.app.domain.models.NotificationToggle
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.models.PushTestPhone
import run.ostrom.app.domain.models.PushTestStatus
import run.ostrom.app.domain.repositories.NotificationPreferencesRepository
import run.ostrom.app.domain.repositories.NotificationSettingsRepository
import run.ostrom.app.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import run.ostrom.app.domain.usecases.notifications.LoadNotificationSettingsUseCase
import run.ostrom.app.domain.usecases.notifications.SaveNotificationPreferencesUseCase
import run.ostrom.app.domain.usecases.notifications.SendTestPushUseCase
import run.ostrom.app.domain.usecases.notifications.SetNotificationSettingUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.navigation.NotificationPreferencesRoute
import run.ostrom.app.presentation.viewmodels.NotificationPreferencesViewModel
import run.ostrom.app.presentation.viewmodels.NotificationSettingsViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelsCoverageTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Preferences : NotificationPreferencesRepository {
        val reads = ArrayDeque<UncancellableGate<NotificationPreferences>>()
        val saves = ArrayDeque<UncancellableGate<NotificationPreferences>>()
        val saved = mutableListOf<NotificationPreferences>()
        override suspend fun preferences(accountId: String): NotificationPreferences = UncancellableGate<NotificationPreferences>().also { reads.addLast(it) }.await()
        override suspend fun save(accountId: String, preferences: NotificationPreferences): NotificationPreferences {
            saved += preferences
            return UncancellableGate<NotificationPreferences>().also { saves.addLast(it) }.await()
        }
    }

    private val held = NotificationPreferences(requests = true, decisions = true, spending = false)

    private fun preferencesModel(repo: Preferences) = NotificationPreferencesViewModel(
        NotificationPreferencesRoute("a1"),
        LoadNotificationPreferencesUseCase(repo, FakeFeatures(setOf(Features.NOTIFICATIONS)), fakeGuard()),
        SaveNotificationPreferencesUseCase(repo, fakeGuard()),
    ) { "Europe/Paris" }

    @Test
    fun aFailedReadSaysWhyAndNothingChangesBeforeItIsRead() = runTest {
        val repo = Preferences()
        val model = preferencesModel(repo)
        model.toggle(NotificationToggle.REQUESTS, false)
        assertTrue(repo.saved.isEmpty())
        repo.reads.removeFirst().fail(AppException.Unreachable())
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        model.toggle(NotificationToggle.REQUESTS, false)
        assertTrue(repo.saved.isEmpty())
        model.refresh()
        repo.reads.removeFirst().release(held)
        assertEquals(held, model.uiState.value.preferences)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun aChangeWaitsForTheReadAndForTheSaveBeforeIt() = runTest {
        val repo = Preferences()
        val model = preferencesModel(repo)
        repo.reads.removeFirst().release(held)
        // A read is on its way: the switches wait for it.
        model.refresh()
        model.toggle(NotificationToggle.REQUESTS, false)
        assertTrue(repo.saved.isEmpty())
        repo.reads.removeFirst().release(held)
        // A switch to the position it is in saves nothing.
        model.toggle(NotificationToggle.REQUESTS, true)
        assertTrue(repo.saved.isEmpty())
        model.toggle(NotificationToggle.REQUESTS, false)
        assertTrue(model.uiState.value.saving)
        // While it is saved: another change and a read are ignored.
        model.toggle(NotificationToggle.DECISIONS, false)
        model.refresh()
        assertEquals(1, repo.saved.size)
        assertTrue(repo.reads.isEmpty())
        repo.saves.removeFirst().release(held.copy(requests = false))
        assertEquals(false, model.uiState.value.preferences?.requests)
        assertFalse(model.uiState.value.saving)
    }

    @Test
    fun aRefusedOrCancelledSaveGoesBackToWhatWasShown() = runTest {
        val repo = Preferences()
        val model = preferencesModel(repo)
        repo.reads.removeFirst().release(held)
        model.setQuietHours(true)
        assertNotNull(model.uiState.value.preferences?.quietHours)
        repo.saves.removeFirst().fail(AppException.Server(400, "invalid"))
        assertEquals(held, model.uiState.value.preferences)
        assertTrue(model.uiState.value.saveFailed)
        assertFalse(model.uiState.value.saving)
        model.setQuietHours(true)
        assertFalse(model.uiState.value.saveFailed)
        repo.saves.removeFirst().fail(CancellationException("closing"))
        assertEquals(held, model.uiState.value.preferences)
        assertFalse(model.uiState.value.saving)
    }

    private class Settings : NotificationSettingsRepository {
        val reads = ArrayDeque<UncancellableGate<NotificationSettings>>()
        val sets = ArrayDeque<UncancellableGate<NotificationSettings>>()
        val tests = ArrayDeque<UncancellableGate<PushTestOutcome>>()
        var setCalls = 0
        override suspend fun settings(accountId: String): NotificationSettings = UncancellableGate<NotificationSettings>().also { reads.addLast(it) }.await()
        override suspend fun set(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean): NotificationSettings {
            setCalls++
            return UncancellableGate<NotificationSettings>().also { sets.addLast(it) }.await()
        }
        override suspend fun test(accountId: String): PushTestOutcome = UncancellableGate<PushTestOutcome>().also { tests.addLast(it) }.await()
    }

    private val initial = NotificationSettings(listOf("push"), emptyMap())

    private fun settingsModel(repo: Settings) = NotificationSettingsViewModel(
        NotificationPreferencesRoute("a1"),
        LoadNotificationSettingsUseCase(repo, FakeFeatures(setOf(Features.NOTIFICATIONS, Features.NOTIFICATIONS_PUSH_TEST)), fakeGuard()),
        SetNotificationSettingUseCase(repo, fakeGuard()),
        SendTestPushUseCase(repo, fakeGuard()),
    )

    @Test
    fun aSwitchWaitsForTheReadAndIgnoresAChangeToItsOwnPosition() = runTest {
        val repo = Settings()
        val model = settingsModel(repo)
        repo.reads.removeFirst().release(initial)
        // A read is on its way when a switch is tapped: it waits.
        model.refresh()
        model.toggle("push", NotificationKind.WAITING, false)
        assertEquals(0, repo.setCalls)
        repo.reads.removeFirst().release(initial)
        // Already on: nothing to save.
        model.toggle("push", NotificationKind.WAITING, true)
        assertEquals(0, repo.setCalls)
        model.toggle("push", NotificationKind.WAITING, false)
        assertEquals(1, repo.setCalls)
        // The same switch again while it is saved.
        model.toggle("push", NotificationKind.WAITING, true)
        assertEquals(1, repo.setCalls)
        repo.sets.removeFirst().release(initial.with("push", NotificationKind.WAITING, false))
        assertEquals(emptySet(), model.uiState.value.saving)
    }

    @Test
    fun aCancelledSwitchGoesBackAndStopsWithoutAnError() = runTest {
        val repo = Settings()
        val model = settingsModel(repo)
        repo.reads.removeFirst().release(initial)
        model.toggle("push", NotificationKind.WAITING, false)
        assertFalse(model.uiState.value.settings!!.isOn("push", NotificationKind.WAITING))
        repo.sets.removeFirst().fail(CancellationException("closing"))
        assertTrue(model.uiState.value.settings!!.isOn("push", NotificationKind.WAITING))
        assertEquals(emptySet<NotificationSwitch>(), model.uiState.value.saving)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun aTestThatFailsOrIsCancelledEndsTheSpinner() = runTest {
        val repo = Settings()
        val model = settingsModel(repo)
        repo.reads.removeFirst().release(initial)
        model.test()
        assertTrue(model.uiState.value.testing)
        repo.tests.removeFirst().fail(CancellationException("closing"))
        assertFalse(model.uiState.value.testing)
        assertNull(model.uiState.value.error)
        model.test()
        repo.tests.removeFirst().fail(AppException.Unreachable())
        assertNotNull(model.uiState.value.error)
        model.test()
        repo.tests.removeFirst().release(PushTestOutcome.Sent(listOf(PushTestPhone(PushTestStatus.SENT))))
        assertNotNull(model.uiState.value.test)
    }
}
