// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.requireSupportedApi
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.OrganizationUseCases
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.auth.SignOutUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.core.api.models.ServerFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountUseCasesTest {
    private val auth = FakeAuthRepository()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()
    private val clock = FixedClock()
    private val refresh = RefreshAccountUseCase(auth, accounts, credentials, clock)

    private suspend fun signedIn(expiresAt: Long? = null) = account(expiresAt = expiresAt).also {
        accounts.save(it)
        credentials.write(it.id, "osm_token")
        credentials.write(unlockKeyEntry(it.id), "unlock")
    }

    @Test
    fun theFeatureKeysAreOstromCores() {
        assertEquals(ServerFeatures.API_VERSION, Features.SUPPORTED_API_VERSION)
        assertEquals(ServerFeatures.GATES, Features.GATES)
        assertEquals(ServerFeatures.RECEIPTS, Features.RECEIPTS)
        assertEquals(ServerFeatures.BUDGETS, Features.BUDGETS)
        assertEquals(ServerFeatures.NOTIFICATIONS, Features.NOTIFICATIONS)
        assertEquals(ServerFeatures.NOTIFICATIONS_PUSH, Features.NOTIFICATIONS_PUSH)
        assertEquals(ServerFeatures.WAITS, Features.WAITS)
        assertEquals(ServerFeatures.ARTIFACTS, Features.ARTIFACTS)
        requireSupportedApi(Features.SUPPORTED_API_VERSION)
        assertFailsWith<AppException.UnsupportedApi> { requireSupportedApi(Features.SUPPORTED_API_VERSION + 1) }
    }

    @Test
    fun signingOutRevokesForgetsAndRemoves() = runTest {
        val account = signedIn()
        val stopped = mutableListOf<String>()
        val result = SignOutUseCase(auth, accounts, credentials) { stopped += it }(account.id)
        assertEquals(SignOutUseCase.Result.REVOKED, result)
        assertEquals(listOf(account.id), stopped)
        assertEquals(listOf(account.id), auth.loggedOut)
        assertEquals(listOf(account.id), auth.released)
        assertNull(credentials.read(account.id))
        assertNull(credentials.read(unlockKeyEntry(account.id)))
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun signingOutOfflineStillSignsOutHere() = runTest {
        val account = signedIn()
        auth.logoutError = AppException.Unreachable()
        assertEquals(SignOutUseCase.Result.SERVER_UNREACHABLE, SignOutUseCase(auth, accounts, credentials)(account.id))
        assertTrue(accounts.accounts.value.isEmpty())
        auth.logoutError = AppException.Unauthorized()
        accounts.save(account)
        assertEquals(SignOutUseCase.Result.REVOKED, SignOutUseCase(auth, accounts, credentials)(account.id))
        assertEquals(SignOutUseCase.Result.REVOKED, SignOutUseCase(auth, accounts, credentials)("unknown"))
    }

    @Test
    fun aRefusedTokenSignsTheAccountOutOnce() = runTest {
        val account = signedIn()
        auth.meError = AppException.Unauthorized()
        val refreshed = refresh(account.id)!!
        assertFalse(refreshed.signedIn)
        assertEquals(1, accounts.accounts.value.size)
        assertNull(credentials.read(account.id))
        assertNull(credentials.read(unlockKeyEntry(account.id)))
        assertEquals(listOf(account.id), auth.released)
    }

    @Test
    fun anExpiredTokenIsNotSentAndOfflineChangesNothing() = runTest {
        val expired = signedIn(expiresAt = clock.now - 1)
        assertFalse(refresh(expired.id)!!.signedIn)
        accounts.remove(expired.id)
        val account = signedIn()
        auth.meError = AppException.Unreachable()
        assertFailsWith<AppException.Unreachable> { refresh(account.id) }
        assertTrue(accounts.accounts.value.single().signedIn)
        assertNull(refresh("unknown"))
    }

    @Test
    fun aRefreshKeepsTheLoginTheServerSays() = runTest {
        val account = signedIn()
        auth.member = Member("ada-lovelace", null)
        assertEquals("ada-lovelace", refresh(account.id)!!.login)
        assertEquals("ada-lovelace", accounts.accounts.value.single().login)
    }

    @Test
    fun theGuardChecksTheAccountOnUnauthorized() = runTest {
        val account = signedIn()
        auth.meError = AppException.Unauthorized()
        val guard = AccountGuard(refresh)
        assertFailsWith<AppException.Unauthorized> { guard(account.id) { throw AppException.Unauthorized() } }
        assertFalse(accounts.accounts.value.single().signedIn)
        assertEquals(3, guard(account.id) { 3 })
    }

    @Test
    fun organizationsAreSortedAndTheChoiceIsKept() = runTest {
        val account = signedIn()
        auth.organizations = listOf(Organization("o2", "beta"), Organization("o1", "Alpha"))
        val organizations = OrganizationUseCases(auth, accounts, AccountGuard(refresh))
        assertEquals(listOf("o1", "o2"), organizations.list(account.id).map { it.id })
        organizations.choose(account.id, Organization("o2", "beta"))
        assertEquals("o2", accounts.accounts.value.single().organizationId)
        assertEquals("beta", accounts.accounts.value.single().organizationName)
        assertNull(organizations.choose("unknown", Organization("o1", "Alpha")))
    }
}
