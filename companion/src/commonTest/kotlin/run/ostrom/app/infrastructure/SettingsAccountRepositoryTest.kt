// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import com.russhwolf.settings.MapSettings
import run.ostrom.app.account
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accounts kept as JSON in the preferences: what a restart reads back, the active account's rules, a damaged value. */
class SettingsAccountRepositoryTest {
    private val settings = MapSettings()

    @Test fun savingAnAccountMakesItActiveAndAnotherOneReplacesTheActiveChoice() {
        val repository = SettingsAccountRepository(settings)
        repository.save(account("a1"))
        repository.save(account("a2", login = "bob"))
        assertEquals(listOf("a1", "a2"), repository.accounts.value.map { it.id })
        assertEquals("a2", repository.activeAccountId.value)
    }

    @Test fun savingAnExistingIdReplacesItInPlaceInsteadOfAddingIt() {
        val repository = SettingsAccountRepository(settings)
        repository.save(account("a1"))
        repository.save(account("a2", login = "bob"))
        repository.save(account("a1", login = "ada-renamed"))
        assertEquals(listOf("a1" to "ada-renamed", "a2" to "bob"), repository.accounts.value.map { it.id to it.login })
        assertEquals("a1", repository.activeAccountId.value)
    }

    @Test fun updatingAnAccountKeepsTheActiveOneAndIgnoresAnUnknownId() {
        val repository = SettingsAccountRepository(settings)
        repository.save(account("a1"))
        repository.save(account("a2", login = "bob"))
        repository.update(account("a1", login = "ada2"))
        repository.update(account("ghost"))
        assertEquals(listOf("ada2", "bob"), repository.accounts.value.map { it.login })
        assertEquals("a2", repository.activeAccountId.value)
    }

    @Test fun accountsAndTheActiveChoiceSurviveARestart() {
        SettingsAccountRepository(settings).apply {
            save(account("a1"))
            save(account("a2", login = "bob"))
            setActive("a1")
        }
        val restarted = SettingsAccountRepository(settings)
        assertEquals(listOf("a1", "a2"), restarted.accounts.value.map { it.id })
        assertEquals("a1", restarted.activeAccountId.value)
    }

    @Test fun anActiveChoiceNamingNoAccountFallsBackToTheFirstOne() {
        SettingsAccountRepository(settings).apply { save(account("a1")); save(account("a2", login = "bob")) }
        settings.putString("accounts.active", "gone")
        assertEquals("a1", SettingsAccountRepository(settings).activeAccountId.value)
    }

    @Test fun settingAnUnknownAccountActiveChangesNothing() {
        val repository = SettingsAccountRepository(settings)
        repository.save(account("a1"))
        repository.setActive("ghost")
        assertEquals("a1", repository.activeAccountId.value)
        assertEquals("a1", settings.getStringOrNull("accounts.active"))
    }

    @Test fun removingTheActiveAccountMovesToTheFirstRemainingOne() {
        val repository = SettingsAccountRepository(settings)
        repository.save(account("a1"))
        repository.save(account("a2", login = "bob"))
        repository.remove("a2")
        assertEquals("a1", repository.activeAccountId.value)
        repository.remove("a1")
        assertNull(repository.activeAccountId.value)
        assertNull(settings.getStringOrNull("accounts.active"), "the stored choice goes with the last account")
        assertTrue(repository.accounts.value.isEmpty())
    }

    @Test fun removingAnInactiveAccountKeepsTheActiveChoice() {
        val repository = SettingsAccountRepository(settings)
        repository.save(account("a1"))
        repository.save(account("a2", login = "bob"))
        repository.remove("a1")
        assertEquals("a2", repository.activeAccountId.value)
        assertEquals(listOf("a2"), repository.accounts.value.map { it.id })
    }

    @Test fun anUnreadableValueIsKeptAsideAndTheAppStartsWithoutAccounts() {
        settings.putString(SettingsAccountRepository.ACCOUNTS_KEY, "{not json")
        val repository = SettingsAccountRepository(settings)
        assertTrue(repository.accounts.value.isEmpty())
        assertNull(repository.activeAccountId.value)
        assertEquals("{not json", settings.getStringOrNull("accounts.v1.unreadable"))
    }

    @Test fun unknownFieldsOfANewerVersionAreIgnored() {
        settings.putString(
            SettingsAccountRepository.ACCOUNTS_KEY,
            """[{"id":"a1","origin":"https://o.example","login":"ada","future":true}]""",
        )
        assertEquals("ada", SettingsAccountRepository(settings).accounts.value.single().login)
    }
}
