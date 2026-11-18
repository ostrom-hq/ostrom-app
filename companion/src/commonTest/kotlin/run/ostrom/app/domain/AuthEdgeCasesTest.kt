// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import run.ostrom.app.CountingRandom
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakePlatform
import run.ostrom.app.FixedClock
import run.ostrom.app.FixedInstallation
import run.ostrom.app.ORIGIN
import run.ostrom.app.SERVER
import run.ostrom.app.SequenceIds
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.services.CliSession
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.BrowserSignInUseCase
import run.ostrom.app.domain.usecases.auth.CompleteSignInUseCase
import run.ostrom.app.domain.usecases.auth.DeviceSignInUseCase
import run.ostrom.app.domain.usecases.auth.OrganizationUseCases
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.auth.SignOutUseCase
import run.ostrom.app.domain.usecases.auth.normalizeOrigin
import run.ostrom.app.domain.usecases.auth.percentDecode
import run.ostrom.app.domain.usecases.auth.queryOfReturn
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A secure store a test breaks: [readError] / [writeError] / [deleteError] are thrown; [onRead] runs during a read. */
class BrokenCredentialStore(
    private val inner: MemoryCredentialStore = MemoryCredentialStore(),
    var readError: Exception? = null,
    var writeError: Exception? = null,
    var deleteError: Exception? = null,
    var onRead: suspend () -> Unit = {},
) : CredentialStore {
    val deleted = mutableListOf<String>()
    override val persistent = true
    override suspend fun read(accountId: String): String? {
        onRead()
        readError?.let { throw it }
        return inner.read(accountId)
    }

    override suspend fun write(accountId: String, token: String) {
        writeError?.let { throw it }
        inner.write(accountId, token)
    }

    override suspend fun delete(accountId: String) {
        deleted += accountId
        deleteError?.let { throw it }
        inner.delete(accountId)
    }
}

class AuthEdgeCasesTest {
    private val auth = FakeAuthRepository()
    private val accounts = accounts()
    private val clock = FixedClock()

    // --- Origins typed by the person ---

    @Test
    fun theTrailingDotOfAFullyQualifiedHostIsDropped() {
        assertEquals("https://ostrom.example.org", normalizeOrigin("ostrom.example.org."))
    }

    @Test
    fun malformedHostsAreRefused() {
        for (typed in listOf("https://.", "ostrom.example.org..", ".example.org", "ostrom..example.org")) {
            assertFailsWith<AppException.InvalidOrigin>(typed) { normalizeOrigin(typed) }
        }
    }

    @Test
    fun portsOutsideTheValidRangeAreRefused() {
        assertFailsWith<AppException.InvalidOrigin> { normalizeOrigin("ostrom.example.org:0") }
        assertFailsWith<AppException.InvalidOrigin> { normalizeOrigin("ostrom.example.org:70000") }
    }

    @Test
    fun theSchemesDefaultPortIsDroppedAndOtherPortsKept() {
        assertEquals("https://ostrom.example.org", normalizeOrigin("https://ostrom.example.org:443"))
        assertEquals("https://ostrom.example.org", normalizeOrigin("https://ostrom.example.org:0443"))
        assertEquals("https://ostrom.example.org:80", normalizeOrigin("https://ostrom.example.org:80"))
        assertEquals("http://localhost", normalizeOrigin("http://localhost:80"))
        assertEquals("http://localhost:443", normalizeOrigin("http://localhost:443"))
        assertEquals("http://127.0.0.1:8080", normalizeOrigin("HTTP://127.0.0.1:8080"))
    }

    // --- The browser's return ---

    @Test
    fun emptyPairsAndNamesWithoutValueAreRead() {
        assertEquals(mapOf("code" to "c", "flag" to "", "state" to "s"), queryOfReturn("ostrom://auth/callback?code=c&&flag&state=s"))
        assertEquals(emptyMap(), queryOfReturn("ostrom://auth/callback#fragment"))
        assertNull(queryOfReturn("ostrom://auth/callback?code=a&code=b"))
    }

    @Test
    fun aLoneHighSurrogateAtTheEndIsKeptAsItIs() {
        assertEquals("a\uD83D".encodeToByteArray().decodeToString(), percentDecode("a\uD83D"))
        assertEquals("😀", percentDecode("😀"))
        assertEquals("%4", percentDecode("%4"))
        assertEquals("%zz ", percentDecode("%zz+"))
    }

    @Test
    fun aReturnToAnotherAddressIsAMismatch() = runTest {
        val signIn = browserSignIn { "ostrom://elsewhere?code=c&state=x" }
        assertFailsWith<AppException.SignInMismatch> { signIn(SERVER) }
    }

    @Test
    fun anEarlierAttemptsReturnConnectsNothingOnceALaterOneStarted() = runTest {
        lateinit var signIn: BrowserSignInUseCase
        var first = true
        var laterState: String? = null
        signIn = browserSignIn { _ ->
            if (first) {
                first = false
                // The person starts again while the first browser is still open; the later attempt is cancelled.
                assertNull(signIn(SERVER))
                laterState = auth.preparedState
                "ostrom://auth/callback?code=c&state=${auth.preparedState}"
            } else {
                null
            }
        }
        assertFailsWith<AppException.SignInMismatch> { signIn(SERVER) }
        assertTrue(auth.exchanges.isEmpty())
        assertEquals(laterState, auth.preparedState)
    }

    @Test
    fun aClosedSignUpWithAnotherStateIsADenial() = runTest {
        val signIn = browserSignIn { "ostrom://auth/callback?error=signup_closed&state=other" }
        assertFailsWith<AppException.SignInDenied> { signIn(SERVER) }
    }

    private fun browserSignIn(answer: suspend (String) -> String?): BrowserSignInUseCase = BrowserSignInUseCase(
        auth, BrowserSignIn { url, _ -> answer(url) }, CountingRandom,
        CompleteSignInUseCase(auth, accounts, MemoryCredentialStore(), SequenceIds()), FixedInstallation(), FakePlatform,
    )

    // --- The device flow ---

    private val login = DeviceLogin(SERVER, "ABCD-EFGH", "https://github.com/login/device", "device-code", intervalMillis = 5_000, expiresInMillis = 60_000)
    private fun device() = DeviceSignInUseCase(auth, CompleteSignInUseCase(auth, accounts, MemoryCredentialStore(), SequenceIds()), clock, FixedInstallation(), FakePlatform) { clock.now += it }

    @Test
    fun aRateLimitOrAServerFailureIsAskedAgain() = runTest {
        auth.polls = mutableListOf({ throw AppException.RateLimited(5) }, { throw AppException.Server(503, null) }, { DevicePoll.Done(auth.exchanged) })
        assertEquals("ada", device().await(login).login)
        assertTrue(auth.polls.isEmpty())
    }

    @Test
    fun aServerRefusalBelow500EndsTheSignIn() = runTest {
        auth.polls = mutableListOf({ throw AppException.Server(400, "bad_request") }, { DevicePoll.Done(auth.exchanged) })
        assertEquals(400, assertFailsWith<AppException.Server> { device().await(login) }.statusCode)
        assertEquals(1, auth.polls.size)
    }

    // --- The guard of an account's calls ---

    @Test
    fun aStreamFailureOtherThanUnauthorizedLeavesTheAccountAlone() = runTest {
        accounts.save(account())
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        val guard = AccountGuard(RefreshAccountUseCase(auth, accounts, store, clock))
        auth.meError = AppException.Unauthorized()
        val failing = flow<Int> { emit(1); throw AppException.Unreachable() }
        assertFailsWith<AppException.Unreachable> { guard.flow("a1", failing).toList() }
        assertTrue(accounts.accounts.value.single().signedIn)
    }

    // --- Re-reading the account ---

    @Test
    fun aRefusingStoreKeepsTheAccountSignedIn() = runTest {
        accounts.save(account())
        val store = BrokenCredentialStore(readError = IllegalStateException("locked"))
        assertFailsWith<AppException.CredentialStorage> { RefreshAccountUseCase(auth, accounts, store, clock)("a1") }
        assertTrue(accounts.accounts.value.single().signedIn)
    }

    @Test
    fun anExpiredSessionIsSignedOutWithoutAsking() = runTest {
        accounts.save(account(expiresAt = clock.now))
        val store = BrokenCredentialStore().also { it.write("a1", "osm_token") }
        val refreshed = RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!
        assertFalse(refreshed.signedIn)
        assertEquals(listOf("a1", unlockKeyEntry("a1")), store.deleted)
        assertEquals(listOf("a1"), auth.released)
    }

    @Test
    fun aSessionNotExpiredYetIsAsked() = runTest {
        accounts.save(account(expiresAt = clock.now + 1))
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        assertTrue(RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!.signedIn)
    }

    @Test
    fun aStoreRefusingTheDeleteStillSignsTheAccountOut() = runTest {
        accounts.save(account())
        val store = BrokenCredentialStore(deleteError = IllegalStateException("locked"))
        val refreshed = RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!
        assertFalse(refreshed.signedIn)
        assertFalse(accounts.accounts.value.single().signedIn)
        assertEquals(listOf("a1", unlockKeyEntry("a1")), store.deleted)
    }

    @Test
    fun anAccountRemovedWhileItsTokenIsReadIsNotSignedOutAgain() = runTest {
        accounts.save(account())
        val store = BrokenCredentialStore(onRead = { accounts.remove("a1") })
        val refreshed = RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!
        assertFalse(refreshed.signedIn)
        assertTrue(accounts.accounts.value.isEmpty())
        assertTrue(auth.released.isEmpty())
    }

    @Test
    fun anAccountSignedInAgainWhileItsTokenIsReadIsKept() = runTest {
        accounts.save(account(expiresAt = clock.now))
        val store = BrokenCredentialStore(onRead = { accounts.update(account(expiresAt = clock.now + 60_000)) })
        val refreshed = RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!
        assertTrue(refreshed.signedIn)
        assertEquals(clock.now + 60_000, refreshed.expiresAt)
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun anAccountSignedOutWhileItsTokenIsReadStaysAsItIs() = runTest {
        accounts.save(account())
        val store = BrokenCredentialStore(onRead = { accounts.update(account(signedIn = false)) })
        assertFalse(RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!.signedIn)
        assertTrue(auth.released.isEmpty())
    }

    @Test
    fun aRefusalForAnAccountRemovedMeanwhileReturnsNothing() = runTest {
        accounts.save(account())
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        val refusing = object : run.ostrom.app.domain.repositories.AuthRepository by auth {
            override suspend fun me(accountId: String): Member {
                accounts.remove(accountId)
                throw AppException.Unauthorized()
            }
        }
        assertNull(RefreshAccountUseCase(refusing, accounts, store, clock)("a1"))
    }

    @Test
    fun aRefusalForAnAccountSignedInAgainMeanwhileKeepsTheNewSession() = runTest {
        accounts.save(account(expiresAt = clock.now + 1_000))
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        val refusing = object : run.ostrom.app.domain.repositories.AuthRepository by auth {
            override suspend fun me(accountId: String): Member {
                accounts.update(account(expiresAt = clock.now + 90_000))
                throw AppException.Unauthorized()
            }
        }
        val refreshed = RefreshAccountUseCase(refusing, accounts, store, clock)("a1")!!
        assertTrue(refreshed.signedIn)
        assertEquals(clock.now + 90_000, refreshed.expiresAt)
    }

    @Test
    fun aRefusalForAnAccountAlreadySignedOutMeanwhileChangesNothing() = runTest {
        accounts.save(account())
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        val refusing = object : run.ostrom.app.domain.repositories.AuthRepository by auth {
            override suspend fun me(accountId: String): Member {
                accounts.update(account(signedIn = false))
                throw AppException.Unauthorized()
            }
        }
        assertFalse(RefreshAccountUseCase(refusing, accounts, store, clock)("a1")!!.signedIn)
        assertTrue(auth.released.isEmpty())
    }

    @Test
    fun anAnswerForAnAccountRemovedOrSignedOutMeanwhileIsDropped() = runTest {
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        accounts.save(account())
        val removing = object : run.ostrom.app.domain.repositories.AuthRepository by auth {
            override suspend fun me(accountId: String): Member = auth.member.also { accounts.remove(accountId) }
        }
        assertNull(RefreshAccountUseCase(removing, accounts, store, clock)("a1"))

        accounts.save(account(login = "old"))
        val signingOut = object : run.ostrom.app.domain.repositories.AuthRepository by auth {
            override suspend fun me(accountId: String): Member = auth.member.also { accounts.update(account(login = "old", signedIn = false)) }
        }
        val refreshed = RefreshAccountUseCase(signingOut, accounts, store, clock)("a1")!!
        assertEquals("old", refreshed.login)
        assertFalse(refreshed.signedIn)
    }

    @Test
    fun theMembershipIsKeptOnlyFromTheAccountsOrganization() = runTest {
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        accounts.save(account(memberId = "m-old").copy(organizationId = "o1"))
        val refresh = RefreshAccountUseCase(auth, accounts, store, clock)

        auth.member = Member("ada", Organization("o2", "Other"), memberId = "m-other")
        assertEquals("m-old", refresh("a1")!!.memberId)

        auth.member = Member("ada", Organization("o1", "Ostrom"), memberId = null)
        assertEquals("m-old", refresh("a1")!!.memberId)

        auth.member = Member("ada", Organization("o1", "Ostrom"), memberId = "m-new")
        assertEquals("m-new", refresh("a1")!!.memberId)
        assertEquals("m-new", accounts.accounts.value.single().memberId)
    }

    // --- The organization the calls act in ---

    @Test
    fun choosingForAnUnknownAccountDoesNothing() {
        val organizations = OrganizationUseCases(auth, accounts, AccountGuard(RefreshAccountUseCase(auth, accounts, MemoryCredentialStore(), clock)))
        assertNull(organizations.choose("missing", Organization("o1", "Ostrom")))
    }

    @Test
    fun choosingTheSameOrganizationAgainKeepsTheMembershipAndWritesNothing() {
        val updates = mutableListOf<Account>()
        val recording = object : run.ostrom.app.domain.repositories.AccountRepository by accounts {
            override fun update(account: Account) { updates += account; this@AuthEdgeCasesTest.accounts.update(account) }
        }
        val current = account(memberId = "m1").copy(organizationId = "o1", organizationName = "Ostrom")
        accounts.save(current)
        val organizations = OrganizationUseCases(auth, recording, AccountGuard(RefreshAccountUseCase(auth, accounts, MemoryCredentialStore(), clock)))

        assertEquals(current, organizations.choose("a1", Organization("o1", "Ostrom")))
        assertTrue(updates.isEmpty())

        assertEquals("m1", organizations.choose("a1", Organization("o1", "Ostrom renamed"))!!.memberId)
        assertNull(organizations.choose("a1", Organization("o2", "Other"))!!.memberId)
        assertEquals(2, updates.size)
    }

    // --- Keeping a new session ---

    @Test
    fun signingInAgainAsTheSameLoginReplacesTheAccountAndRevokesTheOldToken() = runTest {
        val store = MemoryCredentialStore().also { it.write("a1", "old_token") }
        accounts.save(account(memberId = "m1").copy(organizationId = "o1", organizationName = "Ostrom"))
        val complete = CompleteSignInUseCase(auth, accounts, store, SequenceIds())
        auth.tokenOrganizationsError = AppException.Unreachable()
        auth.meError = AppException.Unreachable()

        val replaced = complete(SERVER, NewSession("osm_token", "ada", null, null, null), SignInMethod.DEVICE)

        assertEquals("a1", replaced.id)
        assertEquals("o1", replaced.organizationId)
        assertEquals("Ostrom", replaced.organizationName)
        assertEquals("m1", replaced.memberId)
        assertEquals(listOf("old_token"), auth.abandoned)
        assertEquals("osm_token", store.read("a1"))
        assertNull(store.read(unlockKeyEntry("a1")))
    }

    @Test
    fun signingInAgainWithTheSameTokenRevokesNothing() = runTest {
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        accounts.save(account())
        CompleteSignInUseCase(auth, accounts, store, SequenceIds())(SERVER, auth.exchanged, SignInMethod.BROWSER)
        assertTrue(auth.abandoned.isEmpty())
    }

    @Test
    fun anotherOrganizationDropsTheMembershipOfTheReplacedAccount() = runTest {
        val store = MemoryCredentialStore()
        accounts.save(account(memberId = "m1").copy(organizationId = "o1"))
        auth.organizations = listOf(Organization("o2", "Other"))
        val replaced = CompleteSignInUseCase(auth, accounts, store, SequenceIds())(SERVER, auth.exchanged, SignInMethod.BROWSER)
        assertEquals("o2", replaced.organizationId)
        assertNull(replaced.memberId)
    }

    @Test
    fun anUnreadableReplacedTokenIsNotRevoked() = runTest {
        accounts.save(account())
        val store = BrokenCredentialStore(readError = IllegalStateException("locked"))
        CompleteSignInUseCase(auth, accounts, store, SequenceIds())(SERVER, auth.exchanged, SignInMethod.BROWSER)
        assertTrue(auth.abandoned.isEmpty())
    }

    @Test
    fun aStoreRefusingTheTokenRevokesTheNewSession() = runTest {
        val store = BrokenCredentialStore(writeError = IllegalStateException("locked"))
        assertFailsWith<AppException.CredentialStorage> {
            CompleteSignInUseCase(auth, accounts, store, SequenceIds())(SERVER, auth.exchanged, SignInMethod.BROWSER)
        }
        assertEquals(listOf("osm_token"), auth.abandoned)
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun aServerThatCannotTellItsCapabilitiesIsNotKept() = runTest {
        auth.capabilitiesError = AppException.Unreachable()
        assertFailsWith<AppException.Unreachable> {
            CompleteSignInUseCase(auth, accounts, MemoryCredentialStore(), SequenceIds())(SERVER, auth.exchanged, SignInMethod.BROWSER)
        }
        assertEquals(listOf("osm_token"), auth.abandoned)
    }

    // --- Signing out ---

    private class FakeCli(private val answer: () -> Boolean) : CliSession {
        val asked = mutableListOf<String>()
        override suspend fun signOut(origin: String): Boolean { asked += origin; return answer() }
    }

    @Test
    fun anAccountAlreadySignedOutIsForgottenWithoutAskingTheServer() = runTest {
        accounts.save(account(signedIn = false))
        var before = 0
        val result = SignOutUseCase(auth, accounts, MemoryCredentialStore(), beforeLogout = { before++ })("a1")
        assertEquals(SignOutUseCase.Result.REVOKED, result)
        assertEquals(0, before)
        assertTrue(auth.loggedOut.isEmpty())
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun theCliSignsItsOwnAccountOut() = runTest {
        accounts.save(account(id = Account.CLI_ACCOUNT_ID))
        val cli = FakeCli { true }
        val result = SignOutUseCase(auth, accounts, MemoryCredentialStore(), cli)(Account.CLI_ACCOUNT_ID)
        assertEquals(SignOutUseCase.Result.REVOKED, result)
        assertEquals(listOf(ORIGIN), cli.asked)
        assertTrue(auth.loggedOut.isEmpty())
    }

    @Test
    fun aCliThatFailsLeavesTheRevocationToTheApp() = runTest {
        accounts.save(account(id = Account.CLI_ACCOUNT_ID))
        val cli = FakeCli { throw IllegalStateException("no cli") }
        assertEquals(SignOutUseCase.Result.REVOKED, SignOutUseCase(auth, accounts, MemoryCredentialStore(), cli)(Account.CLI_ACCOUNT_ID))
        assertEquals(listOf(Account.CLI_ACCOUNT_ID), auth.loggedOut)

        accounts.save(account(id = Account.CLI_ACCOUNT_ID))
        assertEquals(SignOutUseCase.Result.REVOKED, SignOutUseCase(auth, accounts, MemoryCredentialStore(), FakeCli { false })(Account.CLI_ACCOUNT_ID))
        assertEquals(2, auth.loggedOut.size)
    }

    @Test
    fun theCliIsNotAskedForAnotherAccount() = runTest {
        accounts.save(account())
        val cli = FakeCli { true }
        SignOutUseCase(auth, accounts, MemoryCredentialStore(), cli)("a1")
        assertTrue(cli.asked.isEmpty())
        assertEquals(listOf("a1"), auth.loggedOut)
    }

    @Test
    fun aTokenTheStoreCannotReadIsAStorageRefusal() = runTest {
        accounts.save(account())
        auth.logoutError = AppException.CredentialStorage()
        assertEquals(SignOutUseCase.Result.STORAGE_REFUSED, SignOutUseCase(auth, accounts, MemoryCredentialStore())("a1"))
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun aStoreRefusingTheDeleteTurnsAnUnrevokedSessionIntoAStorageRefusal() = runTest {
        accounts.save(account())
        auth.logoutError = AppException.Unreachable()
        val store = BrokenCredentialStore(deleteError = IllegalStateException("locked"))
        assertEquals(SignOutUseCase.Result.STORAGE_REFUSED, SignOutUseCase(auth, accounts, store)("a1"))
    }

    @Test
    fun aStoreRefusingTheDeleteOfARevokedSessionStillSaysRevoked() = runTest {
        accounts.save(account())
        val store = BrokenCredentialStore(deleteError = IllegalStateException("locked"))
        assertEquals(SignOutUseCase.Result.REVOKED, SignOutUseCase(auth, accounts, store)("a1"))
        assertEquals(listOf("a1", unlockKeyEntry("a1")), store.deleted)
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun theGuardRethrowsTheFailureItChecked() = runTest {
        accounts.save(account())
        val guard = AccountGuard(RefreshAccountUseCase(auth, accounts, BrokenCredentialStore(readError = IllegalStateException()), clock))
        val thrown = AppException.Unauthorized()
        assertSame(thrown, assertFailsWith<AppException.Unauthorized> { guard("a1") { throw thrown } })
    }

    @Test
    fun theDeviceFlowWaitsTheIntervalOnTheClockByDefault() = runTest {
        auth.polls = mutableListOf({ DevicePoll.Pending() }, { DevicePoll.Done(auth.exchanged) })
        val start = testScheduler.currentTime
        val device = DeviceSignInUseCase(auth, CompleteSignInUseCase(auth, accounts, MemoryCredentialStore(), SequenceIds()), clock, FixedInstallation(), FakePlatform)
        assertEquals("ada", device.await(login).login)
        assertEquals(10_000, testScheduler.currentTime - start)
    }

    @Test
    fun withoutACliTheAppRevokesTheCliAccountItself() = runTest {
        accounts.save(account(id = Account.CLI_ACCOUNT_ID))
        assertEquals(SignOutUseCase.Result.REVOKED, SignOutUseCase(auth, accounts, MemoryCredentialStore())(Account.CLI_ACCOUNT_ID))
        assertEquals(listOf(Account.CLI_ACCOUNT_ID), auth.loggedOut)
    }

    @Test
    fun aMembershipWithoutOrganizationIsKeptOnlyByAnAccountWithoutOne() = runTest {
        val store = MemoryCredentialStore().also { it.write("a1", "osm_token") }
        auth.member = Member("ada", null, memberId = "m1")
        accounts.save(account())
        assertEquals("m1", RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!.memberId)

        accounts.update(account().copy(organizationId = "o1"))
        assertNull(RefreshAccountUseCase(auth, accounts, store, clock)("a1")!!.memberId)
    }

    @Test
    fun theSameLoginOnAnotherServerOrAnotherLoginOnTheSameServerIsANewAccount() = runTest {
        accounts.save(account(origin = "https://other.example.org", memberId = "m1"))
        accounts.save(account(id = "a2", login = "grace"))
        val added = CompleteSignInUseCase(auth, accounts, MemoryCredentialStore(), SequenceIds())(SERVER, auth.exchanged, SignInMethod.BROWSER)
        assertFalse(added.id in setOf("a1", "a2"))
        assertNull(added.organizationId)
        assertNull(added.organizationName)
        assertNull(added.memberId)
        assertEquals(3, accounts.accounts.value.size)
    }

    @Test
    fun anAccountWithoutOrganizationKeepsItsMembershipWhenNoneIsFound() = runTest {
        accounts.save(account(memberId = "m1"))
        val replaced = CompleteSignInUseCase(auth, accounts, MemoryCredentialStore(), SequenceIds())(SERVER, auth.exchanged, SignInMethod.BROWSER)
        assertEquals("a1", replaced.id)
        assertNull(replaced.organizationId)
        assertEquals("m1", replaced.memberId)
    }
}
