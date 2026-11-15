// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with a GitHub link the demo can make; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.GitHubDeviceLink
import run.ostrom.app.domain.models.GitHubLink
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.repositories.GitHubLinkRepository

/** The demo member's GitHub link: [linked] to Ada's account, or not linked on an instance whose GitHub App works. */
internal fun demoGitHubLink(linked: Boolean, now: Long) =
    if (linked) GitHubLink(GitHubLinkState.LINKED, appConfigured = true, login = "ada-lovelace", linkedAt = now)
    else GitHubLink(GitHubLinkState.NOT_LINKED, appConfigured = true)

/**
 * The demo's GitHub link from memory: the device flow shows a code, is pending once, then links Ada's account (no
 * GitHub, no network); unlinking removes it.
 */
class DemoGitHubLinkRepository(private val demo: DemoMode) : GitHubLinkRepository {
    private var pending = false

    override suspend fun link(accountId: String): GitHubLink = demo.store().githubLink.value

    override suspend fun start(accountId: String): GitHubDeviceLink {
        demo.store()
        pending = true
        return GitHubDeviceLink(DEVICE_ID, "DEMO-0000", "https://github.com/login/device", expiresAt = null, interval = 1)
    }

    override suspend fun poll(accountId: String, id: String): GitHubPoll {
        val store = demo.store()
        if (id != DEVICE_ID) return GitHubPoll.Expired
        if (pending) {
            pending = false
            return GitHubPoll.Pending(null)
        }
        val linked = demoGitHubLink(linked = true, store.startedAt)
        store.githubLink.value = linked
        return GitHubPoll.Linked(linked)
    }

    override suspend fun unlink(accountId: String) {
        val store = demo.store()
        store.githubLink.value = demoGitHubLink(linked = false, store.startedAt)
    }

    private companion object { const val DEVICE_ID = "demo-device" }
}

/** The demo account's GitHub link from memory, every other account's from its server. */
class DemoAwareGitHubLinkRepository(private val real: GitHubLinkRepository, private val demo: GitHubLinkRepository) : GitHubLinkRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun link(accountId: String) = of(accountId).link(accountId)
    override suspend fun start(accountId: String) = of(accountId).start(accountId)
    override suspend fun poll(accountId: String, id: String) = of(accountId).poll(accountId, id)
    override suspend fun unlink(accountId: String) = of(accountId).unlink(accountId)
}
