// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/GitHubLinkUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FakeGitHubLinkRepository
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.usecases.github.LoadGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.PollGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.StartGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.UnlinkGitHubUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.gitHubLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubLinkUseCasesTest {
    private val repository = FakeGitHubLinkRepository()
    private val features = FakeFeatures(setOf(Features.GITHUB_LINK))
    private val accounts = accounts().also { it.save(account()) }

    private val load = LoadGitHubLinkUseCase(repository, features, accounts, fakeGuard())

    @Test
    fun theLinkIsReadOnAServerThatOffersIt() = runTest {
        repository.link = gitHubLink()
        assertEquals("octocat", load("a1")?.login)
        assertEquals(listOf("link"), repository.calls)
    }

    @Test
    fun aServerWithoutGitHubLinksAnswersNullAndIsNotAsked() = runTest {
        features.features = setOf(Features.RUNNERS_CLOUD)
        assertNull(load("a1"))
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun aSignedOutOrUnknownAccountIsRefusedBeforeAnyRequest() = runTest {
        accounts.save(account(id = "a2", signedIn = false))
        assertFailsWith<AppException.Unauthorized> { load("a2") }
        assertFailsWith<AppException.Unauthorized> { load("nobody") }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun theFlowStartsPollsAndUnlinks() = runTest {
        repository.polls = listOf({ GitHubPoll.Linked(gitHubLink()) })
        assertEquals("ABCD-1234", StartGitHubLinkUseCase(repository, fakeGuard())("a1").userCode)
        val poll = PollGitHubLinkUseCase(repository, fakeGuard())("a1", "d1")
        assertEquals(GitHubLinkState.LINKED, (poll as GitHubPoll.Linked).link.state)
        UnlinkGitHubUseCase(repository, fakeGuard())("a1")
        assertEquals(listOf("start", "poll d1", "unlink"), repository.calls)
        assertEquals(GitHubLinkState.NOT_LINKED, repository.link.state)
    }
}
