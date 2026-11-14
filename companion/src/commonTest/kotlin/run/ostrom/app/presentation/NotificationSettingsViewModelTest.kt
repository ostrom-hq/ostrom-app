// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../NotificationPreferencesViewModelTest.kt; see NOTICE.
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationSettings
import run.ostrom.app.domain.models.NotificationSwitch
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.models.PushTestPhone
import run.ostrom.app.domain.models.PushTestStatus
import run.ostrom.app.domain.repositories.NotificationSettingsRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.notifications.LoadNotificationSettingsUseCase
import run.ostrom.app.domain.usecases.notifications.SendTestPushUseCase
import run.ostrom.app.domain.usecases.notifications.SetNotificationSettingUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.presentation.navigation.NotificationPreferencesRoute
import run.ostrom.app.presentation.viewmodels.NotificationSettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val accounts = accounts().also { it.save(account()) }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, MemoryCredentialStore(), FixedClock()))
    private val features = FakeFeatures(setOf(Features.NOTIFICATIONS, Features.NOTIFICATIONS_PUSH_TEST))

    private class Fake : NotificationSettingsRepository {
        var held = NotificationSettings(listOf("push"), emptyMap())
        var readFailure: Exception? = null
        var setFailure: Exception? = null
        var testFailure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var outcome: PushTestOutcome = PushTestOutcome.Sent(listOf(PushTestPhone(PushTestStatus.SENT)))
        val calls = mutableListOf<String>()

        override suspend fun settings(accountId: String): NotificationSettings {
            calls += "read"
            readFailure?.let { throw it }
            return held
        }

        override suspend fun set(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean): NotificationSettings {
            calls += "set ${kind.wire} $enabled"
            gate?.await()
            setFailure?.let { throw it }
            held = held.with(channel, kind, enabled)
            return held
        }

        override suspend fun test(accountId: String): PushTestOutcome {
            calls += "test"
            gate?.await()
            testFailure?.let { throw it }
            return outcome
        }
    }

    private val repository = Fake()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = NotificationSettingsViewModel(
        NotificationPreferencesRoute("a1"),
        LoadNotificationSettingsUseCase(repository, features, guard),
        SetNotificationSettingUseCase(repository, guard),
        SendTestPushUseCase(repository, guard),
    )

    @Test
    fun theSwitchesLoadWithWhetherATestIsOffered() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.loading)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf("push"), state.settings?.channels)
        assertTrue(state.canTest)

        features.features = setOf(Features.NOTIFICATIONS)
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canTest)
        viewModel.test()
        assertEquals(listOf("read", "read"), repository.calls, "no test without the feature")
    }

    @Test
    fun aFailedReadSaysWhyAndARetryLoads() = runTest(dispatcher) {
        repository.readFailure = AppException.Unreachable()
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.settings)
        repository.readFailure = null
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.settings)
    }

    @Test
    fun aSwitchFlipsAtOnceAndKeepsWhatTheServerSaved() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.gate = CompletableDeferred()
        viewModel.toggle("push", NotificationKind.WAITING, false)
        runCurrent()
        val switch = NotificationSwitch("push", NotificationKind.WAITING)
        assertEquals(setOf(switch), viewModel.uiState.value.saving)
        assertFalse(viewModel.uiState.value.settings!!.isOn("push", NotificationKind.WAITING))
        // While it is saved: the same switch again, a switch to its own position and a read are ignored.
        viewModel.toggle("push", NotificationKind.WAITING, true)
        viewModel.toggle("push", NotificationKind.TURN_FAILED, true)
        viewModel.refresh()
        repository.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptySet(), viewModel.uiState.value.saving)
        assertFalse(viewModel.uiState.value.settings!!.isOn("push", NotificationKind.WAITING))
        assertEquals(listOf("read", "set waiting false"), repository.calls)
    }

    @Test
    fun aRefusedSwitchGoesBackAndSaysWhy() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.setFailure = AppException.Server(400, "invalid_notification_setting")
        viewModel.toggle("push", NotificationKind.SESSION_FAILED, false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.settings!!.isOn("push", NotificationKind.SESSION_FAILED))
        assertEquals(emptySet(), viewModel.uiState.value.saving)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun nothingIsSavedBeforeTheSwitchesAreRead() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggle("push", NotificationKind.WAITING, false)
        advanceUntilIdle()
        assertEquals(listOf("read"), repository.calls)
    }

    @Test
    fun aTestShowsEachPhoneAndOneAtATime() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.gate = CompletableDeferred()
        viewModel.test()
        runCurrent()
        assertTrue(viewModel.uiState.value.testing)
        viewModel.test()
        repository.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.testing)
        assertEquals(PushTestOutcome.Sent(listOf(PushTestPhone(PushTestStatus.SENT))), viewModel.uiState.value.test)
        assertEquals(listOf("read", "test"), repository.calls)

        repository.gate = null
        repository.outcome = PushTestOutcome.NoPhone
        viewModel.test()
        advanceUntilIdle()
        assertEquals(PushTestOutcome.NoPhone, viewModel.uiState.value.test)
    }

    @Test
    fun aRateLimitedTestSaysWhenToTryAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.testFailure = AppException.RateLimited(10)
        viewModel.test()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.testing)
        assertNull(viewModel.uiState.value.test)
        assertEquals("Too many attempts. Try again in 10 s.", viewModel.uiState.value.error?.text)
    }
}
