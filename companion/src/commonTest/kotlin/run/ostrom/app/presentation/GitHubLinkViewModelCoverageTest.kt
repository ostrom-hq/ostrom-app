// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubDeviceLink
import run.ostrom.app.domain.models.GitHubLink
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.models.GitHubRefusal
import run.ostrom.app.domain.repositories.GitHubLinkRepository
import run.ostrom.app.domain.usecases.github.LoadGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.PollGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.StartGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.UnlinkGitHubUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.gitHubLink
import run.ostrom.app.presentation.navigation.GitHubLinkRoute
import run.ostrom.app.presentation.viewmodels.GitHubLinkViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The GitHub link screen's guards while something is in flight, and the endings the first test file does not reach. */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubLinkViewModelCoverageTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Repo : GitHubLinkRepository {
        var link: GitHubLink = gitHubLink()
        var device = GitHubDeviceLink("d1", "ABCD-1234", "https://github.com/login/device", expiresAt = null, interval = 5)
        var onStart: suspend () -> GitHubDeviceLink = { device }
        var onPoll: suspend () -> GitHubPoll = { GitHubPoll.Pending(null) }
        var onUnlink: suspend () -> Unit = { link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null) }
        var links = 0
        var starts = 0
        var unlinks = 0

        override suspend fun link(accountId: String): GitHubLink { links++; return link }
        override suspend fun start(accountId: String): GitHubDeviceLink { starts++; return onStart() }
        override suspend fun poll(accountId: String, id: String): GitHubPoll = onPoll()
        override suspend fun unlink(accountId: String) { unlinks++; onUnlink() }
    }

    private val repo = Repo()

    private val created = mutableListOf<GitHubLinkViewModel>()

    /** A device flow polls for ever: the view models are closed at the end, or the test would follow the poll to the end of time. */
    private fun coverageTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            created.forEach { it.viewModelScope.cancel() }
        }
    }

    private fun model(): GitHubLinkViewModel {
        val guard = fakeGuard()
        val accounts = accounts().also { it.save(account()) }
        return GitHubLinkViewModel(
            GitHubLinkRoute("a1"), LoadGitHubLinkUseCase(repo, FakeFeatures(setOf(Features.GITHUB_LINK)), accounts, guard),
            StartGitHubLinkUseCase(repo, guard), PollGitHubLinkUseCase(repo, guard), UnlinkGitHubUseCase(repo, guard),
        ).also { created += it }
    }

    private fun TestScope.settle() = runCurrent()

    @Test fun nothingCanBeLinkedOrRemovedBeforeTheLinkIsRead() = coverageTest {
        val model = model()
        assertNull(model.uiState.value.link)
        assertFalse(model.uiState.value.canLink)
        assertFalse(model.uiState.value.canUnlink)
        settle()
        assertTrue(model.uiState.value.canLink && model.uiState.value.canUnlink)
    }

    @Test fun neitherActionIsOfferedWhileAStartIsInFlightAndASecondStartIsIgnored() = coverageTest {
        val gate = CompletableDeferred<Unit>()
        repo.onStart = { gate.await(); repo.device }
        val model = model(); settle()
        model.startLinking(); settle()
        assertTrue(model.uiState.value.starting)
        assertFalse(model.uiState.value.canLink)
        assertFalse(model.uiState.value.canUnlink)
        model.startLinking(); settle()
        assertEquals(1, repo.starts)
        gate.complete(Unit); settle()
        // A device flow in progress holds both buttons too.
        assertNotNull(model.uiState.value.step)
        assertFalse(model.uiState.value.canLink)
        assertFalse(model.uiState.value.canUnlink)
        model.unlinkAccount(); settle()
        assertEquals(0, repo.unlinks)
        model.dismissLinked()
        assertNull(model.uiState.value.justLinked)
    }

    @Test fun neitherActionIsOfferedWhileUnlinkingAndASecondUnlinkIsIgnored() = coverageTest {
        val gate = CompletableDeferred<Unit>()
        repo.onUnlink = { gate.await(); repo.link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null) }
        val model = model(); settle()
        model.unlinkAccount(); settle()
        assertTrue(model.uiState.value.unlinking)
        assertFalse(model.uiState.value.canLink)
        assertFalse(model.uiState.value.canUnlink)
        model.unlinkAccount(); settle()
        gate.complete(Unit); settle()
        assertEquals(1, repo.unlinks)
        assertFalse(model.uiState.value.unlinking)
        assertEquals(GitHubLinkState.NOT_LINKED, model.uiState.value.link?.state)
        // Not linked: nothing to remove.
        model.unlinkAccount(); settle()
        assertEquals(1, repo.unlinks)
    }

    @Test fun anUnlinkThatFailsKeepsItsSentenceAndOneThatIsCancelledShowsNoError() = coverageTest {
        repo.onUnlink = { throw AppException.Unreachable() }
        val model = model(); settle()
        model.unlinkAccount(); settle()
        assertFalse(model.uiState.value.unlinking)
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
        assertEquals(GitHubLinkState.LINKED, model.uiState.value.link?.state)
        repo.onUnlink = { throw CancellationException("cancelled") }
        model.unlinkAccount(); settle()
        assertFalse(model.uiState.value.unlinking, "the button is released")
        assertNull(model.uiState.value.error, "a cancellation is not a failure to show")
    }

    @Test fun aStartThatCannotReachTheServerIsNotReadAgain() = coverageTest {
        repo.onStart = { throw AppException.Unreachable() }
        val model = model(); settle()
        model.startLinking(); settle()
        assertEquals("The server could not be reached.", model.uiState.value.error?.text)
        assertNull(model.uiState.value.step)
        assertFalse(model.uiState.value.starting)
        assertEquals(1, repo.links, "only a refusal of the server reads the link again")
    }

    @Test fun aPollRefusedByTheServerEndsTheFlowAndReadsTheLinkAgain() = coverageTest {
        repo.onPoll = { throw AppException.GitHubRefused(GitHubRefusal.APP_MISCONFIGURED) }
        val model = model(); settle()
        model.startLinking(); settle()
        assertNotNull(model.uiState.value.step)
        advanceTimeBy(5_000); settle()
        assertNull(model.uiState.value.step, "a refusal that is not a network failure is not waited out")
        assertEquals(AppException.GitHubRefused(GitHubRefusal.APP_MISCONFIGURED).message, model.uiState.value.error?.text)
        assertEquals(2, repo.links)
    }

    @Test fun theBrowserOpenedAndCancellingWithoutAFlowChangeNothing() = coverageTest {
        val model = model(); settle()
        val before = model.uiState.value
        model.browserOpened()
        model.cancelLinking()
        assertEquals(before, model.uiState.value)
        assertNull(model.uiState.value.step)
    }
}
