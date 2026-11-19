// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.ORIGIN
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.demo.DemoContent
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.MemberSetupRepository
import run.ostrom.app.domain.usecases.setup.LoadMemberEnvironmentUseCase
import run.ostrom.app.domain.usecases.setup.LoadPersonalMcpServersUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.navigation.MemberEnvironmentRoute
import run.ostrom.app.presentation.navigation.PersonalMcpServersRoute
import run.ostrom.app.presentation.viewmodels.MemberEnvironmentViewModel
import run.ostrom.app.presentation.viewmodels.PersonalMcpServersViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The read-only member setup screens: the content, the failure, a newer refresh replacing an older one, and the console link. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberSetupViewModelsTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Setup : MemberSetupRepository {
        var environment: suspend () -> MemberEnvironment = { MemberEnvironment(listOf("TOKEN"), emptyList(), emptyList()) }
        var servers: suspend () -> List<PersonalMcpServer> = { emptyList() }
        override suspend fun environment(accountId: String) = environment()
        override suspend fun mcpServers(accountId: String) = servers()
    }

    private val setup = Setup()
    private val features = FakeFeatures(setOf(Features.ENVIRONMENT, Features.MCP_PERSONAL))

    private fun environment(accounts: AccountRepository, id: String = "a1") =
        MemberEnvironmentViewModel(MemberEnvironmentRoute(id), accounts, LoadMemberEnvironmentUseCase(setup, features, fakeGuard()))

    private fun servers(accounts: AccountRepository, id: String = "a1") =
        PersonalMcpServersViewModel(PersonalMcpServersRoute(id), accounts, LoadPersonalMcpServersUseCase(setup, features, fakeGuard()))

    @Test fun theConsoleLinkNeedsASignedInAccountThatIsNotTheDemo() = runTest {
        val repository = accounts().also {
            it.save(account("a1"))
            it.save(account("out", signedIn = false))
            it.save(account(DemoContent.ACCOUNT_ID))
        }
        assertEquals("$ORIGIN/me/environment", environment(repository).uiState.value.consoleUrl)
        assertEquals("$ORIGIN/me/mcp", servers(repository).uiState.value.consoleUrl)
        assertNull(environment(repository, "out").uiState.value.consoleUrl, "a signed-out account has no console to open")
        assertNull(environment(repository, DemoContent.ACCOUNT_ID).uiState.value.consoleUrl, "the demo has no console")
        assertNull(environment(repository, "gone").uiState.value.consoleUrl, "an account that no longer exists has none")
    }

    @Test fun theContentIsReadOnCreationAndAFailureKeepsWhatWasRead() = runTest {
        val repository = accounts().also { it.save(account()) }
        val model = environment(repository)
        assertEquals(listOf("TOKEN"), model.uiState.value.content?.names)
        assertFalse(model.uiState.value.loading)
        assertNull(model.uiState.value.error)
        setup.environment = { throw AppException.Unreachable() }
        model.refresh()
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
        assertEquals(listOf("TOKEN"), model.uiState.value.content?.names, "the last content stays while a refresh fails")
        assertFalse(model.uiState.value.loading)
        val mcp = servers(repository)
        assertEquals(emptyList(), mcp.uiState.value.content)
    }

    @Test fun aNewerRefreshReplacesTheOlderOne() = runTest {
        val repository = accounts().also { it.save(account()) }
        val first = CompletableDeferred<MemberEnvironment>()
        setup.environment = { first.await() }
        val model = environment(repository)
        assertTrue(model.uiState.value.loading)
        setup.environment = { MemberEnvironment(listOf("NEWER"), emptyList(), emptyList()) }
        model.refresh()
        assertEquals(listOf("NEWER"), model.uiState.value.content?.names)
        first.complete(MemberEnvironment(listOf("OLDER"), emptyList(), emptyList()))
        assertEquals(listOf("NEWER"), model.uiState.value.content?.names, "the older answer never lands over the newer one")
        assertFalse(model.uiState.value.loading)
    }
}
