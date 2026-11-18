// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.models.ThemePreference
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.BrowserSignInUseCase
import run.ostrom.app.domain.usecases.auth.CompleteSignInUseCase
import run.ostrom.app.domain.usecases.auth.DeviceSignInUseCase
import run.ostrom.app.domain.usecases.auth.InspectServerUseCase
import run.ostrom.app.domain.usecases.auth.OrganizationUseCases
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.auth.SignOutUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsPreferencesRepository
import run.ostrom.app.presentation.navigation.LoginRoute
import run.ostrom.app.presentation.viewmodels.HomeViewModel
import run.ostrom.app.presentation.viewmodels.LoginViewModel
import run.ostrom.app.presentation.viewmodels.SettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLoginSettingsViewModelsTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val clock = FixedClock()
    private val credentials = MemoryCredentialStore()
    private val auth = FakeAuthRepository()

    private class Features(var caps: ServerCapabilities = ServerCapabilities("1.4.0", 1, setOf("gates.v1"))) : FeaturesRepository {
        var gate: CompletableDeferred<ServerCapabilities>? = null
        var reads = 0
        var cachedCaps: ServerCapabilities? = null
        override suspend fun capabilities(accountId: String, refresh: Boolean): ServerCapabilities {
            reads++
            return gate?.await() ?: caps
        }
        override fun cached(accountId: String): ServerCapabilities? = cachedCaps
    }

    /** An account repository whose active id may name no account. */
    private class FixedAccounts(list: List<Account>, active: String?) : AccountRepository {
        override val accounts = MutableStateFlow(list)
        override val activeAccountId: StateFlow<String?> = MutableStateFlow(active)
        override fun save(account: Account) = Unit
        override fun update(account: Account) = Unit
        override fun remove(id: String) = Unit
        override fun setActive(id: String) = Unit
    }

    private fun home(accounts: AccountRepository, features: Features) =
        HomeViewModel(accounts, RefreshAccountUseCase(auth, accounts, credentials, clock), features, clock)

    @Test
    fun aSignedOutOrMissingAccountHasNoServerToRead() = runTest {
        val features = Features()
        val repository = accounts().also { it.save(account(signedIn = false)) }
        val signedOut = home(repository, features)
        assertEquals("a1", signedOut.uiState.value.account?.id)
        assertNull(signedOut.uiState.value.capabilities)
        assertFalse(signedOut.uiState.value.serves("gates.v1"))
        // An active id that names no account is the same: nothing to read.
        val ghost = home(FixedAccounts(emptyList(), "ghost"), features)
        assertNull(ghost.uiState.value.account)
        assertEquals(0, features.reads)
    }

    @Test
    fun theServerIsReadAtStartAndAgainWhenTheAppReturnsAfterTheWait() = runTest {
        credentials.write("a1", "tok")
        val features = Features()
        val repository = accounts().also { it.save(account()) }
        val model = home(repository, features)
        assertTrue(model.uiState.value.serves("gates.v1"))
        assertFalse(model.uiState.value.serves("sessions.v1"))
        assertEquals(1, features.reads)
        // Just checked: the foreground does not ask again.
        model.onForeground()
        assertEquals(1, features.reads)
        clock.now += HomeViewModel.RECHECK_AFTER_MILLIS
        features.gate = CompletableDeferred()
        model.onForeground()
        assertTrue(model.uiState.value.refreshing)
        // One already running is not doubled.
        model.onForeground()
        assertEquals(2, features.reads)
        features.gate!!.complete(ServerCapabilities("1.5.0", 1, setOf("sessions.v1")))
        assertTrue(model.uiState.value.serves("sessions.v1"))
        assertFalse(model.uiState.value.refreshing)
        // Without an active account there is nothing to ask.
        val none = home(accounts(), features)
        none.onForeground()
        assertEquals(2, features.reads)
    }

    @Test
    fun aRefreshInFlightIsDroppedWhenTheAccountChangesAndItsSpinnerWithIt() = runTest {
        credentials.write("a1", "tok")
        credentials.write("a2", "tok")
        val features = Features()
        val repository = accounts().also { it.save(account()) }
        val model = home(repository, features)
        clock.now += HomeViewModel.RECHECK_AFTER_MILLIS
        features.gate = CompletableDeferred()
        features.cachedCaps = ServerCapabilities("1.4.0", 1, setOf("cached.v1"))
        model.onForeground()
        assertTrue(model.uiState.value.refreshing)
        repository.save(account(id = "a2", login = "grace"))
        features.gate!!.complete(ServerCapabilities("1.4.0", 1, setOf("later.v1")))
        assertEquals("a2", model.uiState.value.account?.id)
        assertFalse(model.uiState.value.refreshing)
        // The first account's own refresh was cancelled too: nothing of it is left spinning.
        features.gate = CompletableDeferred()
        repository.setActive("a1")
        assertTrue(model.uiState.value.refreshing)
        repository.setActive("a2")
        assertEquals("a2", model.uiState.value.account?.id)
    }

    @Test
    fun anOtherApiVersionBlocksTheAppAndAFailureKeepsWhatWasKnown() = runTest {
        credentials.write("a1", "tok")
        val features = Features(ServerCapabilities("9.0.0", 2, emptySet()))
        val repository = accounts().also { it.save(account()) }
        val incompatible = home(repository, features)
        assertEquals(2, incompatible.uiState.value.incompatible?.apiVersion)
        assertNull(incompatible.uiState.value.error)
        auth.meError = AppException.Unreachable()
        features.cachedCaps = ServerCapabilities("1.4.0", 1, setOf("gates.v1"))
        val offline = home(repository, features)
        assertEquals("The server could not be reached.", offline.uiState.value.error?.text)
        assertTrue(offline.uiState.value.serves("gates.v1"))
        assertNull(offline.uiState.value.incompatible)
    }

    private fun login(browser: BrowserSignInUseCase?, sleep: suspend (Long) -> Unit = {}): LoginViewModel {
        val complete = CompleteSignInUseCase(auth, accounts(), credentials, SequenceIds())
        return LoginViewModel(
            LoginRoute(ORIGIN),
            InspectServerUseCase(auth),
            browser,
            DeviceSignInUseCase(auth, complete, clock, FixedInstallation(), FakePlatform, sleep),
        )
    }

    @Test
    fun theDeviceFlowShowsItsCodeCanBeCancelledAndComesBack() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = login(null) { gate.await() }
        assertFalse(model.usesBrowser)
        assertEquals(ORIGIN, model.uiState.value.server?.origin)
        model.signIn()
        assertTrue(model.uiState.value.busy)
        assertEquals("ABCD-EFGH", model.uiState.value.deviceLogin?.userCode)
        // A sign-in already running is not started twice.
        model.signIn()
        assertEquals(1, auth.installations.size)
        model.cancel()
        assertFalse(model.uiState.value.busy)
        assertNull(model.uiState.value.deviceLogin)
        assertNull(model.uiState.value.error)
        model.cancel()
        auth.polls = mutableListOf({ DevicePoll.Done(NewSession("osm_token", "ada", null, null)) })
        gate.complete(Unit)
        model.signIn()
        assertEquals("ada", model.uiState.value.signedIn?.login)
        assertFalse(model.uiState.value.busy)
    }

    @Test
    fun aServerWithoutGitHubOrOutOfReachSaysSoAndCanBeReadAgain() = runTest {
        auth.discoverError = AppException.Unreachable()
        val model = login(null)
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
        assertNull(model.uiState.value.server)
        // Nothing to sign in on yet.
        model.signIn()
        assertFalse(model.uiState.value.busy)
        auth.discoverError = null
        auth.server = ServerInfo(ORIGIN, methods = emptySet())
        model.loadServer()
        assertNull(model.uiState.value.error)
        model.signIn()
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.busy)
        assertNull(model.uiState.value.deviceLogin)
    }

    @Test
    fun aPhoneSignsInWithTheBrowserAndAClosedBrowserIsNoFailure() = runTest {
        val complete = CompleteSignInUseCase(auth, accounts(), credentials, SequenceIds())
        var closed = true
        val browser = BrowserSignInUseCase(auth, BrowserSignIn { _, _ -> if (closed) null else "ostrom://auth/callback?code=c0de&state=${auth.preparedState}" }, CountingRandom, complete, FixedInstallation(), FakePlatform)
        val model = login(browser)
        assertTrue(model.usesBrowser)
        model.signIn()
        assertNull(model.uiState.value.signedIn)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.busy)
        closed = false
        model.signIn()
        assertEquals("ada", model.uiState.value.signedIn?.login)
    }

    private fun settings(accounts: AccountRepository, preferences: SettingsPreferencesRepository, features: Features, platformAuth: FakeAuthRepository = auth) = SettingsViewModel(
        accounts,
        preferences,
        features,
        SignOutUseCase(platformAuth, accounts, credentials),
        OrganizationUseCases(platformAuth, accounts, AccountGuard(RefreshAccountUseCase(platformAuth, accounts, credentials, clock))),
        credentials,
        FakePlatform,
    )

    @Test
    fun settingsSwitchAccountsThemesAndOrganizations() = runTest {
        val repository = accounts().also { it.save(account()); it.save(account(id = "a2", login = "grace")) }
        val preferences = SettingsPreferencesRepository(MapSettings())
        val features = Features()
        val model = settings(repository, preferences, features)
        assertEquals("a2", model.uiState.value.activeAccountId)
        assertEquals(1, features.caps.apiVersion)
        assertEquals("1.4.0", model.uiState.value.server?.serverVersion)
        model.switchTo(repository.accounts.value.first { it.id == "a1" })
        assertEquals("a1", model.uiState.value.activeAccountId)
        // The server read for another account is never shown under this one.
        model.setTheme(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, model.uiState.value.theme)
        // Organizations: read for the active account, chosen on it.
        auth.organizations = listOf(Organization("o2", "Zeta"), Organization("o1", "Alpha"))
        model.loadOrganizations()
        assertEquals(listOf("Alpha", "Zeta"), model.uiState.value.organizations?.map { it.name })
        model.chooseOrganization(Organization("o1", "Alpha"))
        assertEquals("o1", repository.accounts.value.first { it.id == "a1" }.organizationId)
        // Another account does not show these organizations.
        repository.setActive("a2")
        assertNull(model.uiState.value.organizations)
    }

    @Test
    fun settingsDoNothingWithoutAnActiveAccountAndShowFailuresAndSignOuts() = runTest {
        val preferences = SettingsPreferencesRepository(MapSettings())
        val none = settings(accounts(), preferences, Features())
        none.loadOrganizations()
        none.chooseOrganization(Organization("o1", "Alpha"))
        assertNull(none.uiState.value.organizations)
        assertFalse(none.uiState.value.working)
        // An organization list that cannot be read is a message, and dismissing it clears it.
        val repository = accounts().also { it.save(account()) }
        val model = settings(repository, preferences, Features())
        auth.organizationsError = AppException.Unreachable()
        model.loadOrganizations()
        assertEquals("The server could not be reached.", model.uiState.value.message?.text)
        model.dismissMessage()
        assertNull(model.uiState.value.message)
        // Signing out says whether the server could be told.
        auth.logoutError = AppException.Unreachable()
        model.signOut(repository.accounts.value.single())
        assertTrue(model.uiState.value.message?.text.orEmpty().contains("could not be reached"))
        assertTrue(repository.accounts.value.isEmpty())
    }

    @Test
    fun aSignOutThatCannotReadItsTokenAndOneThatIsRevokedAreWorded() = runTest {
        val preferences = SettingsPreferencesRepository(MapSettings())
        val repository = accounts().also { it.save(account()) }
        val model = settings(repository, preferences, Features())
        auth.logoutError = AppException.CredentialStorage()
        model.signOut(repository.accounts.value.single())
        assertTrue(model.uiState.value.message?.text.orEmpty().contains("secure storage"))
        repository.save(account(id = "a3", login = "linus"))
        auth.logoutError = null
        model.signOut(repository.accounts.value.single())
        assertEquals("Signed out of linus on $ORIGIN.", model.uiState.value.message?.text)
    }

    @Test
    fun anActionWaitsForTheRunningOneAndACancelledOneLeavesNoSpinner() = runTest {
        val preferences = SettingsPreferencesRepository(MapSettings())
        val repository = accounts().also { it.save(account()) }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : FakeAuthRepository() {
            override suspend fun organizations(accountId: String): List<Organization> { gate.await(); return super.organizations(accountId) }
        }
        val model = settings(repository, preferences, Features(), slow)
        model.loadOrganizations()
        assertTrue(model.uiState.value.working)
        model.loadOrganizations()
        gate.complete(Unit)
        assertFalse(model.uiState.value.working)
        // The call is cancelled under the action: no message, no stuck spinner.
        val cancelled = FakeAuthRepository().also { it.organizationsError = kotlinx.coroutines.CancellationException("closing") }
        val other = settings(repository, preferences, Features(), cancelled)
        other.loadOrganizations()
        assertFalse(other.uiState.value.working)
        assertNull(other.uiState.value.message)
    }
}
