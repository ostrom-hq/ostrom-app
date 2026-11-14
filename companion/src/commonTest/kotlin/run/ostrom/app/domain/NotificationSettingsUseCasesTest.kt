// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion NotificationPreferencesViewModelTest (Apache-2.0); see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NotificationChannels
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
import run.ostrom.core.api.models.NotificationKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationSettingsUseCasesTest {
    private val push = NotificationChannels.PUSH

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts(), MemoryCredentialStore(), FixedClock()))

    private class FakeSettings(var held: NotificationSettings) : NotificationSettingsRepository {
        val calls = mutableListOf<String>()
        var outcome: PushTestOutcome = PushTestOutcome.NoPhone
        override suspend fun settings(accountId: String): NotificationSettings { calls += "read"; return held }
        override suspend fun set(accountId: String, channel: String, kind: NotificationKind, enabled: Boolean): NotificationSettings {
            calls += "set $channel ${kind.wire} $enabled"; held = held.with(channel, kind, enabled); return held
        }
        override suspend fun test(accountId: String): PushTestOutcome { calls += "test"; return outcome }
    }

    @Test
    fun theKindsAreOstromCores() {
        assertEquals(NotificationKinds.ALL, NotificationKind.entries.map { it.wire })
        assertEquals(NotificationKind.TURN_FAILED, NotificationKind.parse(NotificationKinds.TURN_FAILED))
        assertNull(NotificationKind.parse("deployed"))
    }

    @Test
    fun aSwitchTheServerDoesNotListIsOnAndEachChangesItsOwnChannelAndKind() {
        val settings = NotificationSettings(listOf(push, "slack"), mapOf(NotificationSwitch(push, NotificationKind.WAITING) to false))
        assertFalse(settings.isOn(push, NotificationKind.WAITING))
        assertTrue(settings.isOn(push, NotificationKind.TURN_COMPLETED))
        assertTrue(settings.isOn("slack", NotificationKind.WAITING))
        val changed = settings.with("slack", NotificationKind.WAITING, false).with(push, NotificationKind.WAITING, true)
        assertFalse(changed.isOn("slack", NotificationKind.WAITING))
        assertTrue(changed.isOn(push, NotificationKind.WAITING))
        assertEquals(listOf(push, "slack"), changed.channels)
    }

    @Test
    fun aTestCountsThePhonesThatTookItAndAnUnknownStatusIsOther() {
        assertEquals(PushTestStatus.SENT, PushTestStatus.parse("sent"))
        assertEquals(PushTestStatus.UNREGISTERED, PushTestStatus.parse("unregistered"))
        assertEquals(PushTestStatus.RETRY, PushTestStatus.parse("retry"))
        assertEquals(PushTestStatus.REJECTED, PushTestStatus.parse("rejected"))
        assertEquals(PushTestStatus.OTHER, PushTestStatus.parse("queued"))
        assertEquals(PushTestStatus.OTHER, PushTestStatus.parse(""))
        val sent = PushTestOutcome.Sent(listOf(PushTestPhone(PushTestStatus.SENT), PushTestPhone(PushTestStatus.REJECTED, "bad_token")))
        assertEquals(1, sent.delivered)
        assertEquals(0, PushTestOutcome.Sent(emptyList()).delivered)
    }

    @Test
    fun aServerWithoutNotificationsIsNotAskedAndTheTestNeedsItsFeature() = runTest {
        val repository = FakeSettings(NotificationSettings(listOf(push), emptyMap()))
        val features = FakeFeatures()
        val load = LoadNotificationSettingsUseCase(repository, features, guard())
        assertFailsWith<AppException.Unsupported> { load("a1") }
        assertEquals(emptyList(), repository.calls)

        features.features = setOf(Features.NOTIFICATIONS)
        val view = load("a1")
        assertEquals(listOf(push), view.settings.channels)
        assertFalse(view.canTest)

        features.features = setOf(Features.NOTIFICATIONS, Features.NOTIFICATIONS_PUSH_TEST)
        assertTrue(load("a1").canTest)
    }

    @Test
    fun aSwitchIsSavedAndATestSent() = runTest {
        val repository = FakeSettings(NotificationSettings(listOf(push), emptyMap()))
        val saved = SetNotificationSettingUseCase(repository, guard())("a1", push, NotificationKind.TURN_COMPLETED, false)
        assertFalse(saved.isOn(push, NotificationKind.TURN_COMPLETED))
        repository.outcome = PushTestOutcome.Off
        assertEquals(PushTestOutcome.Off, SendTestPushUseCase(repository, guard())("a1"))
        assertEquals(listOf("set push turn_completed false", "test"), repository.calls)
    }
}
