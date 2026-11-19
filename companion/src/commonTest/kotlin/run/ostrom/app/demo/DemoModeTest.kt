// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FixedClock
import run.ostrom.app.ORIGIN
import run.ostrom.app.account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.credentials.SecureReceiptKeyPinRepository
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The demo's life: it starts on an empty device, holds its state in memory, ends with its account. */
class DemoModeTest {
    private val clock = FixedClock(5_000_000L)
    private val demo = DemoMode(clock)
    private val settings = MapSettings()
    private val real = SettingsAccountRepository(settings)
    private val accounts = DemoAccountRepository(real, demo)

    @Test fun startingTheDemoGivesItsAccountAndEndingItDropsEverything() {
        assertNull(demo.account)
        assertFalse(demo.active)
        val started = demo.start()
        assertEquals(DemoContent.ACCOUNT_ID, started.id)
        assertEquals(started, demo.account)
        assertTrue(demo.active)
        demo.end()
        assertNull(demo.account)
        assertFalse(demo.active)
        assertFailsWith<AppException.NotFound> { demo.store() }
    }

    @Test fun restartingTheDemoStartsItsContentFromScratch() {
        demo.start()
        demo.store().waits.value = emptyList()
        clock.now += 60_000
        demo.start()
        assertTrue(demo.store().waits.value.isNotEmpty())
        assertEquals(clock.now, demo.store().startedAt)
    }

    @Test fun theDemoIsOfferedOnlyOnADeviceWithoutAnyAccountAndStartsOnItsOwnAccount() {
        val start = StartDemoUseCase(accounts)
        assertTrue(start.offered)
        val account = start()
        assertEquals(DemoContent.ACCOUNT_ID, account.id)
        assertFalse(start.offered, "the demo account is an account")
        assertEquals(listOf(DemoContent.ACCOUNT_ID), accounts.accounts.value.map { it.id })
        assertEquals(DemoContent.ACCOUNT_ID, accounts.activeAccountId.value)
        assertTrue(real.accounts.value.isEmpty(), "nothing of the demo is written to the device")
        assertNull(settings.getStringOrNull(SettingsAccountRepository.ACCOUNTS_KEY))
    }

    @Test fun theDemoIsNotOfferedBesideARealAccountNorWithAnotherAccountRepository() {
        real.save(account("a1"))
        val demoAware = StartDemoUseCase(DemoAccountRepository(real, demo))
        assertFalse(demoAware.offered)
        assertFailsWith<IllegalStateException> { demoAware() }
        val plain = StartDemoUseCase(SettingsAccountRepository(MapSettings()))
        assertFalse(plain.offered, "a test's own repository is not the demo-aware one")
        assertFailsWith<IllegalStateException> { plain() }
    }

    @Test fun aRealAccountSavedEndsTheDemoAndTheRealOnesAreListedAfterIt() {
        accounts.startDemo()
        accounts.save(account("a1"))
        assertFalse(demo.active)
        assertEquals(listOf("a1"), accounts.accounts.value.map { it.id })
        assertEquals("a1", accounts.activeAccountId.value)
    }

    @Test fun theDemoAccountIsSavedAndUpdatedInMemoryOnly() {
        accounts.startDemo()
        accounts.save(DemoContent.account().copy(organizationName = "Renamed"))
        assertEquals("Renamed", demo.account?.organizationName)
        accounts.update(DemoContent.account().copy(organizationName = "Again"))
        assertEquals("Again", accounts.accounts.value.single().organizationName)
        assertTrue(real.accounts.value.isEmpty())
    }

    @Test fun updatingTheDemoAccountAfterTheDemoEndedChangesNothing() {
        accounts.startDemo()
        demo.end()
        accounts.update(DemoContent.account())
        assertFalse(demo.active)
        assertTrue(accounts.accounts.value.isEmpty(), "the ended demo's account is gone from the list")
    }

    @Test fun aRealAccountIsUpdatedRemovedAndMadeActiveThroughTheRealRepository() {
        real.save(account("a1"))
        real.save(account("a2", login = "bob"))
        accounts.update(account("a1", login = "ada2"))
        assertEquals(listOf("ada2", "bob"), accounts.accounts.value.map { it.login })
        accounts.setActive("a1")
        assertEquals("a1", accounts.activeAccountId.value)
        accounts.remove("a1")
        assertEquals(listOf("a2"), accounts.accounts.value.map { it.id })
    }

    @Test fun makingTheDemoActiveAndRemovingItEndsTheDemo() {
        accounts.startDemo()
        accounts.setActive(DemoContent.ACCOUNT_ID)
        assertEquals(DemoContent.ACCOUNT_ID, accounts.activeAccountId.value)
        accounts.remove(DemoContent.ACCOUNT_ID)
        assertFalse(demo.active)
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test fun theDemoHasNoTokenAndNoneIsReadWrittenOrDeletedForIt() = runTest {
        val store = MemoryCredentialStore()
        val credentials = DemoCredentialStore(store)
        assertEquals("demo", credentials.read(DemoContent.ACCOUNT_ID))
        assertEquals("demo", credentials.read("${DemoContent.ACCOUNT_ID}.unlock"))
        credentials.write(DemoContent.ACCOUNT_ID, "secret")
        assertNull(store.read(DemoContent.ACCOUNT_ID), "nothing is written for the demo")
        credentials.write("a1", "osm_1")
        assertEquals("osm_1", credentials.read("a1"))
        credentials.delete(DemoContent.ACCOUNT_ID)
        credentials.delete("a1")
        assertNull(store.read("a1"))
        assertEquals(store.persistent, credentials.persistent)
    }

    @Test fun theDemoConfirmsWithoutBiometricsWhileItRunsAndThePlatformDoesOtherwise() = runTest {
        val platform = object : BiometricGate {
            override val supported = false
            override suspend fun confirm(prompt: String) = BiometricOutcome.entries.first { it != BiometricOutcome.CONFIRMED }
        }
        val gate = DemoAwareBiometricGate(platform, demo)
        assertFalse(gate.supported)
        assertNotEquals(BiometricOutcome.CONFIRMED, gate.confirm("Approve"))
        demo.start()
        assertTrue(gate.supported)
        assertEquals(BiometricOutcome.CONFIRMED, gate.confirm("Approve"))
    }

    @Test fun theDemoServersPinsStayInMemoryAndOtherServersGoToTheSecureStore() = runTest {
        demo.start()
        val secure = SecureReceiptKeyPinRepository(MemoryCredentialStore())
        val pins = DemoAwareReceiptKeyPinRepository(secure, demo)
        assertEquals(emptyMap(), pins.read(DemoContent.ORIGIN))
        pins.write(DemoContent.ORIGIN, mapOf("k" to "ab"))
        assertEquals(mapOf("k" to "ab"), pins.read(DemoContent.ORIGIN))
        assertEquals(emptyMap(), secure.read(DemoContent.ORIGIN), "not written to the device")
        pins.write(ORIGIN, mapOf("k2" to "cd"))
        assertEquals(mapOf("k2" to "cd"), pins.read(ORIGIN))
        assertEquals(mapOf("k2" to "cd"), secure.read(ORIGIN))
    }

    @Test fun theDemoServerSignsNobodyInAndAnswersItsOwnAccountFromMemory() = runTest {
        demo.start()
        val server = DemoAuthRepository(demo)
        assertFailsWith<AppException.NotOstrom> { server.discover(DemoContent.ORIGIN) }
        assertFailsWith<AppException.NotOstrom> { server.prepareBrowserSignIn(DemoContent.ORIGIN, "r", "s") }
        assertFailsWith<AppException.NotOstrom> { server.exchangeCode(DemoContent.ORIGIN, "c", "v", "r", null) }
        assertFailsWith<AppException.NotOstrom> { server.startDeviceLogin(ServerInfo(DemoContent.ORIGIN), null) }
        assertFailsWith<AppException.NotOstrom> { server.pollDeviceLogin(DemoContent.ORIGIN, "d") }
        assertFailsWith<AppException.NotOstrom> { server.capabilities(DemoContent.ORIGIN, "t") }
        assertFailsWith<AppException.NotOstrom> { server.organizations(DemoContent.ORIGIN, "t") }
        assertFailsWith<AppException.NotOstrom> { server.me(DemoContent.ORIGIN, "t") }
        assertEquals(DemoContent.features, server.capabilities(DemoContent.ACCOUNT_ID).features)
        assertEquals(Features.SUPPORTED_API_VERSION, server.capabilities(DemoContent.ACCOUNT_ID).apiVersion)
        assertEquals("Analytical Engines", server.organizations(DemoContent.ACCOUNT_ID).single().name)
        assertEquals("owner", server.organizations(DemoContent.ACCOUNT_ID).single().role)
        assertEquals(Member("ada", null), server.me(DemoContent.ACCOUNT_ID))
        server.logout(DemoContent.ACCOUNT_ID)
        server.release(DemoContent.ACCOUNT_ID)
        server.abandon(DemoContent.ORIGIN, "t")
        val features = DemoFeaturesRepository(server)
        assertEquals(DemoContent.features, features.capabilities(DemoContent.ACCOUNT_ID, refresh = true).features)
        assertEquals(DemoContent.SERVER_VERSION, features.cached(DemoContent.ACCOUNT_ID)?.serverVersion)
        demo.end()
        assertFailsWith<AppException.NotFound> { server.capabilities(DemoContent.ACCOUNT_ID) }
        assertFailsWith<AppException.NotFound> { server.me(DemoContent.ACCOUNT_ID) }
    }

    @Test fun aDemoMemberWhoLeftTheOrganizationHasBlankOnes() = runTest {
        demo.start()
        demo.store().account.value = DemoContent.account().copy(organizationId = null, organizationName = null)
        val organization = DemoAuthRepository(demo).organizations(DemoContent.ACCOUNT_ID).single()
        assertEquals("" to "", organization.id to organization.name)
        assertEquals("owner", organization.role)
    }
}
