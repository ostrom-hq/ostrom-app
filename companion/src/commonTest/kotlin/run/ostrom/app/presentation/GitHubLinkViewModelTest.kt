// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../presentation/GitHubLinkViewModelTest.kt; see NOTICE.
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FakeGitHubLinkRepository
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.models.GitHubRefusal
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.github.LoadGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.PollGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.StartGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.UnlinkGitHubUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.gitHubLink
import run.ostrom.app.presentation.navigation.GitHubLinkRoute
import run.ostrom.app.presentation.viewmodels.GitHubLinkEnd
import run.ostrom.app.presentation.viewmodels.GitHubLinkViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val repository = FakeGitHubLinkRepository()
    private val features = FakeFeatures(setOf(Features.GITHUB_LINK))
    private lateinit var model: GitHubLinkViewModel

    private fun model(accounts: AccountRepository = accounts().also { it.save(account()) }): GitHubLinkViewModel {
        val guard = fakeGuard()
        return GitHubLinkViewModel(GitHubLinkRoute("a1"), LoadGitHubLinkUseCase(repository, features, accounts, guard), StartGitHubLinkUseCase(repository, guard),
            PollGitHubLinkUseCase(repository, guard), UnlinkGitHubUseCase(repository, guard))
    }

    private fun TestScope.settle() = runCurrent()

    @Test fun theLinkIsReadWhenTheServerOffersIt() = runTest(dispatcher) {
        repository.link = gitHubLink()
        model = model(); settle()
        assertEquals(listOf("link"), repository.calls)
        val state = model.uiState.value
        assertEquals("octocat", state.link?.login)
        assertTrue(state.canLink && state.canUnlink)
    }

    @Test fun aServerWithoutGitHubLinksShowsNoLinkAndCannotStart() = runTest(dispatcher) {
        features.features = emptySet()
        model = model(); settle()
        assertTrue(repository.calls.isEmpty())
        assertFalse(model.uiState.value.offered)
        model.startLinking(); settle()
        assertNull(model.uiState.value.step)
        assertTrue(repository.calls.none { it == "start" })
    }

    @Test fun withoutTheInstancesAppNothingCanBeLinked() = runTest(dispatcher) {
        repository.link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null, appConfigured = false)
        model = model(); settle()
        assertFalse(model.uiState.value.canLink)
        model.startLinking(); settle()
        assertTrue(repository.calls.none { it == "start" })
    }

    @Test fun theDeviceFlowIsPolledAtTheServersIntervalUntilLinked() = runTest(dispatcher) {
        repository.polls = listOf({ GitHubPoll.Pending(null) }, { GitHubPoll.Pending(10) }, { GitHubPoll.Linked(gitHubLink()) })
        model = model(); settle()
        model.startLinking(); settle()
        val step = model.uiState.value.step!!
        assertEquals("ABCD-1234", step.code)
        assertEquals("https://github.com/login/device", step.verificationUri)
        assertFalse(step.opened)
        model.browserOpened()
        assertTrue(model.uiState.value.step!!.opened)
        advanceTimeBy(4_999); settle()
        assertTrue(repository.calls.none { it.startsWith("poll") }, "Nothing is polled before the first interval")
        advanceTimeBy(1); settle()
        assertEquals(1, repository.calls.count { it.startsWith("poll") })
        advanceTimeBy(5_000); settle()
        assertEquals(2, repository.calls.count { it.startsWith("poll") })
        // GitHub asked to slow down to 10 s.
        advanceTimeBy(9_999); settle()
        assertEquals(2, repository.calls.count { it.startsWith("poll") })
        advanceTimeBy(1); settle()
        val state = model.uiState.value
        assertEquals(3, repository.calls.count { it.startsWith("poll") })
        assertNull(state.step)
        assertEquals("octocat", state.link?.login)
        assertEquals("octocat", state.justLinked)
        assertTrue(state.canUnlink)
    }

    @Test fun anExpiredOrDeniedFlowEndsWithItsReasonAndCanStartAgain() = runTest(dispatcher) {
        repository.polls = listOf({ GitHubPoll.Expired })
        model = model(); settle()
        model.startLinking(); advanceTimeBy(5_000); settle()
        assertEquals(GitHubLinkEnd.EXPIRED, model.uiState.value.ended)
        assertNull(model.uiState.value.step)
        repository.polls = listOf({ GitHubPoll.Denied })
        model.startLinking(); settle()
        assertNull(model.uiState.value.ended, "A new attempt clears the last ending")
        advanceTimeBy(5_000); settle()
        assertEquals(GitHubLinkEnd.DENIED, model.uiState.value.ended)
    }

    @Test fun cancellingStopsThePolling() = runTest(dispatcher) {
        model = model(); settle()
        model.startLinking(); settle()
        model.cancelLinking()
        advanceTimeBy(60_000); settle()
        assertNull(model.uiState.value.step)
        assertTrue(repository.calls.none { it.startsWith("poll") })
    }

    @Test fun aRefusedStartKeepsItsReasonAndReadsTheLinkAgain() = runTest(dispatcher) {
        repository.startFailure = AppException.GitHubRefused(GitHubRefusal.DEVICE_FLOW_DISABLED)
        model = model(); settle()
        model.startLinking(); settle()
        val state = model.uiState.value
        assertNull(state.step)
        assertContains(state.error!!.text, "device flow")
        assertEquals(listOf("link", "start", "link"), repository.calls)
    }

    @Test fun aFewNetworkFailuresAreWaitedOutButNotMany() = runTest(dispatcher) {
        var failures = 0
        repository.polls = listOf({ failures++; throw AppException.Unreachable() })
        model = model(); settle()
        model.startLinking(); settle()
        advanceTimeBy(5_000); settle()
        advanceTimeBy(5_000); settle()
        assertNotNull(model.uiState.value.step, "Two failures in a row are waited out")
        advanceTimeBy(5_000); settle()
        assertEquals(3, failures)
        assertNull(model.uiState.value.step)
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
    }

    @Test fun anotherFailureEndsTheFlowAtOnce() = runTest(dispatcher) {
        repository.polls = listOf({ throw AppException.Server(502, "bad_gateway") })
        model = model(); settle()
        model.startLinking(); advanceTimeBy(5_000); settle()
        assertNull(model.uiState.value.step)
        assertContains(model.uiState.value.error!!.text, "bad_gateway")
        assertEquals(1, repository.calls.count { it.startsWith("poll") })
    }

    @Test fun unlinkingRemovesTheLinkAndReadsItAgain() = runTest(dispatcher) {
        repository.link = gitHubLink()
        model = model(); settle()
        model.unlinkAccount(); settle()
        assertEquals("unlink", repository.calls.dropLast(1).last())
        val state = model.uiState.value
        assertEquals(GitHubLinkState.NOT_LINKED, state.link?.state)
        assertFalse(state.canUnlink)
        assertFalse(state.unlinking)
    }

    @Test fun aSignedOutAccountCannotLoad() = runTest(dispatcher) {
        model = model(accounts().also { it.save(account(signedIn = false)) }); settle()
        assertTrue(repository.calls.isEmpty())
        assertNotNull(model.uiState.value.error)
    }

    @Test fun githubNotAnsweringTheServerIsWaitedOutLikeANetworkFailure() = runTest(dispatcher) {
        repository.polls = listOf({ throw AppException.GitHubRefused(GitHubRefusal.UNREACHABLE) }, { throw AppException.GitHubRefused(GitHubRefusal.UNREACHABLE) }, { GitHubPoll.Linked(gitHubLink()) })
        model = model(); settle()
        model.startLinking(); settle()
        advanceTimeBy(5_000); settle()
        advanceTimeBy(5_000); settle()
        assertNotNull(model.uiState.value.step)
        advanceTimeBy(5_000); settle()
        assertEquals("octocat", model.uiState.value.justLinked)
    }

    @Test fun theSecondsOfTheServerAreKeptBetweenOneAndSixty() = runTest(dispatcher) {
        repository.device = repository.device.copy(interval = 0)
        repository.polls = listOf({ GitHubPoll.Pending(600) }, { GitHubPoll.Linked(gitHubLink()) })
        model = model(); settle()
        model.startLinking(); settle()
        advanceTimeBy(999); settle()
        assertEquals(0, repository.calls.count { it.startsWith("poll") }, "No faster than every second")
        advanceTimeBy(1); settle()
        assertEquals(1, repository.calls.count { it.startsWith("poll") })
        advanceTimeBy(59_999); settle()
        assertEquals(1, repository.calls.count { it.startsWith("poll") }, "No slower than every minute")
        advanceTimeBy(1); settle()
        assertEquals(2, repository.calls.count { it.startsWith("poll") })
    }

    @Test fun aLinkInAStateThisAppDoesNotKnowCanBeLinkedAgainOrRemoved() = runTest(dispatcher) {
        repository.link = gitHubLink(GitHubLinkState.UNKNOWN)
        model = model(); settle()
        assertTrue(model.uiState.value.canLink && model.uiState.value.canUnlink)
    }
}
