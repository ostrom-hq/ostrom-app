// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.CountingRandom
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.ORIGIN
import run.ostrom.app.SERVER
import run.ostrom.app.SequenceIds
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.domain.usecases.auth.BrowserSignInUseCase
import run.ostrom.app.domain.usecases.auth.CompleteSignInUseCase
import run.ostrom.app.domain.usecases.auth.DeviceSignInUseCase
import run.ostrom.app.domain.usecases.auth.InspectServerUseCase
import run.ostrom.app.domain.usecases.auth.base64Url
import run.ostrom.app.domain.usecases.auth.normalizeOrigin
import run.ostrom.app.domain.usecases.auth.percentDecode
import run.ostrom.app.domain.usecases.auth.queryOfReturn
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInUseCasesTest {
    private val auth = FakeAuthRepository()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()
    private val complete = CompleteSignInUseCase(auth, accounts, credentials, SequenceIds())
    private val state = base64Url(CountingRandom.nextBytes(16))

    private fun browser(answer: (url: String) -> String?) = BrowserSignInUseCase(auth, BrowserSignIn { url, scheme ->
        assertEquals("ostrom", scheme)
        answer(url)
    }, CountingRandom, complete)

    @Test
    fun originsAreNormalized() {
        assertEquals("https://ostrom.example.org", normalizeOrigin("ostrom.example.org"))
        assertEquals("https://ostrom.example.org", normalizeOrigin("  https://ostrom.example.org/  "))
        assertFailsWith<AppException.InvalidOrigin> { normalizeOrigin("http://ostrom.example.org") }
        assertFailsWith<AppException.InvalidOrigin> { normalizeOrigin("") }
    }

    @Test
    fun inspectAsksTheNormalizedOrigin() = runTest {
        assertEquals(ORIGIN, InspectServerUseCase(auth)("ostrom.example.org/").origin)
    }

    @Test
    fun aBrowserSignInKeepsTheTokenTheUnlockKeyAndTheAccount() = runTest {
        val account = browser { "ostrom://auth/callback?code=c0de&state=${auth.preparedState}" }(SERVER)!!
        assertEquals("ada", account.login)
        assertEquals("Ada Lovelace", account.name)
        assertEquals(SignInMethod.BROWSER, account.method)
        assertEquals("1.4.0", account.serverVersion)
        assertEquals("osm_token", credentials.read(account.id))
        assertEquals("unlock-key", credentials.read(unlockKeyEntry(account.id)))
        assertEquals(listOf(account), accounts.accounts.value)
        assertEquals(account.id, accounts.activeAccountId.value)
        assertEquals(Triple("c0de", "verifier", "ostrom://auth/callback"), auth.exchanges.single())
        assertEquals(state, auth.preparedState)
    }

    @Test
    fun aClosedBrowserConnectsNothing() = runTest {
        assertNull(browser { null }(SERVER))
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun aReturnWithAnotherStateIsRefused() = runTest {
        assertFailsWith<AppException.SignInMismatch> { browser { "ostrom://auth/callback?code=c0de&state=other" }(SERVER) }
        assertFailsWith<AppException.SignInMismatch> { browser { "ostrom://elsewhere?code=c0de&state=$state" }(SERVER) }
        assertTrue(auth.exchanges.isEmpty())
    }

    @Test
    fun aRefusalIsNamedOnlyWithItsState() = runTest {
        assertFailsWith<AppException.SignUpClosed> { browser { "ostrom://auth/callback?error=signup_closed&state=$state" }(SERVER) }
        assertFailsWith<AppException.SignInDenied> { browser { "ostrom://auth/callback?error=signup_closed&state=other" }(SERVER) }
        assertFailsWith<AppException.SignInDenied> { browser { "ostrom://auth/callback?error=access_denied&state=$state" }(SERVER) }
    }

    @Test
    fun aServerWithoutGitHubIsRefusedBeforeTheBrowserOpens() = runTest {
        val noGitHub = SERVER.copy(methods = setOf("email"))
        assertFailsWith<AppException.NoGitHubSignIn> { browser { error("opened") }(noGitHub) }
        assertFailsWith<AppException.NoGitHubSignIn> { DeviceSignInUseCase(auth, complete) {}.start(noGitHub) }
    }

    @Test
    fun anUnsupportedApiRevokesTheNewSession() = runTest {
        auth.capabilities = auth.capabilities.copy(apiVersion = 2)
        val error = assertFailsWith<AppException.UnsupportedApi> { complete(SERVER, NewSession("osm_new", "ada", null, null), SignInMethod.DEVICE) }
        assertEquals(2, error.apiVersion)
        assertEquals(listOf("osm_new"), auth.abandoned)
        assertTrue(accounts.accounts.value.isEmpty())
        assertNull(credentials.read("id-1"))
    }

    @Test
    fun theOnlyOrganizationIsChosenElseTheOneMeNames() = runTest {
        auth.organizations = listOf(Organization("o1", "Analytical Engines"))
        assertEquals("o1", complete(SERVER, NewSession("t1", "ada", null, null), SignInMethod.DEVICE).organizationId)
        auth.organizations = listOf(Organization("o1", "A"), Organization("o2", "B"))
        auth.member = Member("ada", Organization("o2", "B"))
        assertEquals("o2", complete(SERVER, NewSession("t2", "ada", null, null), SignInMethod.DEVICE).organizationId)
    }

    @Test
    fun signingInAgainReplacesTheAccountAndRevokesTheOldSession() = runTest {
        val first = complete(SERVER, NewSession("t1", "ada", null, null, "k1"), SignInMethod.BROWSER)
        val second = complete(SERVER, NewSession("t2", "ada", null, null), SignInMethod.DEVICE)
        assertEquals(first.id, second.id)
        assertEquals(1, accounts.accounts.value.size)
        assertEquals("t2", credentials.read(first.id))
        assertNull(credentials.read(unlockKeyEntry(first.id)))
        assertEquals(listOf("t1"), auth.abandoned)
    }

    @Test
    fun aRefusingStoreRevokesTheSession() = runTest {
        val refusing = object : CredentialStore {
            override val persistent = true
            override suspend fun read(accountId: String): String? = null
            override suspend fun write(accountId: String, token: String) = error("locked")
            override suspend fun delete(accountId: String) = error("locked")
        }
        val failing = CompleteSignInUseCase(auth, accounts, refusing, SequenceIds())
        assertFailsWith<AppException.CredentialStorage> { failing(SERVER, NewSession("t", "ada", null, null), SignInMethod.DEVICE) }
        assertEquals(listOf("t"), auth.abandoned)
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun theDeviceFlowWaitsSlowsDownAndRetriesUntilYes() = runTest {
        val pauses = mutableListOf<Long>()
        auth.polls = mutableListOf(
            { DevicePoll.Pending() },
            { DevicePoll.Pending(slowDown = true) },
            { throw AppException.Unreachable() },
            { throw AppException.Server(503, null) },
            { DevicePoll.Done(NewSession("osm_cli", "ada", null, null)) },
        )
        val device = DeviceSignInUseCase(auth, complete) { pauses += it }
        val account = device.await(device.start(SERVER))
        assertEquals(SignInMethod.DEVICE, account.method)
        assertEquals(listOf(5_000L, 5_000L, 10_000L, 10_000L, 10_000L), pauses)
        assertEquals("osm_cli", credentials.read(account.id))
    }

    @Test
    fun theDeviceFlowEndsWhenTheCodeExpiresOrIsRefused() = runTest {
        auth.polls = MutableList(20) { { DevicePoll.Pending() } }
        val device = DeviceSignInUseCase(auth, complete) {}
        assertFailsWith<AppException.SignInExpired> { device.await(device.start(SERVER)) }
        auth.polls = mutableListOf({ throw AppException.Unreachable() }, { throw AppException.SignInDenied() })
        assertFailsWith<AppException.SignInDenied> { device.await(device.start(SERVER)) }
        auth.polls = MutableList(20) { { throw AppException.Unreachable() } }
        assertFailsWith<AppException.Unreachable> { device.await(device.start(SERVER)) }
    }

    @Test
    fun returnsAreParsedStrictly() {
        assertEquals(mapOf("code" to "a b", "state" to "s/1"), queryOfReturn("ostrom://auth/callback?code=a+b&state=s%2F1#frag"))
        assertEquals(emptyMap(), queryOfReturn("ostrom://auth/callback"))
        assertNull(queryOfReturn("ostrom://auth/callback2?code=x"))
        assertEquals("100%", percentDecode("100%"))
        assertEquals("é", percentDecode("%C3%A9"))
    }
}
