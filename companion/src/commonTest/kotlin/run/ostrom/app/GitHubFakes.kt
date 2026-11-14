// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../GitHubFakes.kt; see NOTICE.
package run.ostrom.app

import run.ostrom.app.domain.models.GitHubDeviceLink
import run.ostrom.app.domain.models.GitHubLink
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.repositories.GitHubLinkRepository

fun gitHubLink(state: GitHubLinkState = GitHubLinkState.LINKED, login: String? = "octocat", appConfigured: Boolean = true) =
    GitHubLink(state, appConfigured, login, linkedAt = 1_700_000_000_000L)

/** The member's GitHub link held in memory; every call is recorded in [calls]. */
class FakeGitHubLinkRepository : GitHubLinkRepository {
    val calls = mutableListOf<String>()
    var link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null)
    var device = GitHubDeviceLink("d1", "ABCD-1234", "https://github.com/login/device", expiresAt = 1_900_000_000_000L, interval = 5)

    /** The answers to successive polls; the last one repeats. */
    var polls: List<() -> GitHubPoll> = listOf({ GitHubPoll.Pending(null) })
    var startFailure: Exception? = null
    private var polled = 0

    override suspend fun link(accountId: String): GitHubLink { calls += "link"; return link }
    override suspend fun start(accountId: String): GitHubDeviceLink { calls += "start"; startFailure?.let { throw it }; return device }
    override suspend fun poll(accountId: String, id: String): GitHubPoll {
        calls += "poll $id"
        val answer = polls[minOf(polled++, polls.lastIndex)]()
        if (answer is GitHubPoll.Linked) link = answer.link
        return answer
    }
    override suspend fun unlink(accountId: String) { calls += "unlink"; link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null) }
}
