// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.NotificationToggle
import run.ostrom.app.domain.models.QuietHours
import run.ostrom.app.domain.repositories.NotificationPreferencesRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import run.ostrom.app.domain.usecases.notifications.SaveNotificationPreferencesUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NotificationPreferencesUseCasesTest {
    private val all = NotificationPreferences(requests = true, decisions = true, spending = true)

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts(), MemoryCredentialStore(), FixedClock()))

    private class FakePreferences(var held: NotificationPreferences) : NotificationPreferencesRepository {
        val calls = mutableListOf<String>()
        override suspend fun preferences(accountId: String): NotificationPreferences { calls += "read"; return held }
        override suspend fun save(accountId: String, preferences: NotificationPreferences): NotificationPreferences {
            calls += "save"; held = preferences; return held
        }
    }

    @Test
    fun eachSwitchChangesItsOwnKindOnly() {
        assertEquals(listOf(true, true, true), NotificationToggle.entries.map { all[it] })
        assertEquals(all.copy(requests = false), all.with(NotificationToggle.REQUESTS, false))
        assertEquals(all.copy(decisions = false), all.with(NotificationToggle.DECISIONS, false))
        assertEquals(all.copy(spending = false), all.with(NotificationToggle.SPENDING, false))
        assertEquals(listOf(false, true, true), NotificationToggle.entries.map { all.with(NotificationToggle.REQUESTS, false)[it] })
    }

    @Test
    fun quietHoursTurnOnAtNightInThePhonesZoneAndKeepTheirsWhenAlreadyOn() {
        val on = all.withQuietHours(true, "Europe/Paris")
        assertEquals(QuietHours("22:00", "07:00", "Europe/Paris"), on.quietHours)
        val own = all.copy(quietHours = QuietHours("23:00", "08:00", "America/New_York"))
        assertEquals(own, own.withQuietHours(true, "Europe/Paris"))
        assertNull(own.withQuietHours(false, "Europe/Paris").quietHours)
    }

    @Test
    fun aServerWithoutPreferencesIsNotAsked() = runTest {
        val repository = FakePreferences(all); val features = FakeFeatures()
        val load = LoadNotificationPreferencesUseCase(repository, features, guard())
        assertFailsWith<AppException.Unsupported> { load("a1") }
        assertEquals(emptyList(), repository.calls)
        features.features = setOf(Features.NOTIFICATIONS)
        assertEquals(all, load("a1"))
        val saved = SaveNotificationPreferencesUseCase(repository, guard())("a1", all.copy(spending = false))
        assertEquals(false, saved.spending)
        assertEquals(listOf("read", "save"), repository.calls)
    }
}
