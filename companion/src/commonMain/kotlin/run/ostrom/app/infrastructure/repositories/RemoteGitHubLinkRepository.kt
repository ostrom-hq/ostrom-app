// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteGitHubLinkRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.GitHubDeviceLink
import run.ostrom.app.domain.models.GitHubLink
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.models.GitHubRefusal
import run.ostrom.app.domain.repositories.GitHubLinkRepository
import run.ostrom.core.api.models.GitHubDeviceLinkStatus
import run.ostrom.core.client.OstromException
import run.ostrom.core.api.models.GitHubDeviceLink as ApiGitHubDeviceLink
import run.ostrom.core.api.models.GitHubLink as ApiGitHubLink
import run.ostrom.core.api.models.GitHubLinkState as ApiGitHubLinkState

/**
 * The member's GitHub link over the typed client (`/api/v1/github/link`, `…/link/device`). The server holds the GitHub
 * token; the app only shows the device flow's code, and reads the account that came out of it.
 */
class RemoteGitHubLinkRepository(private val calls: AccountCalls) : GitHubLinkRepository {
    override suspend fun link(accountId: String): GitHubLink = calls.withAccount(accountId) { it.githubLink.get().domain() }

    override suspend fun start(accountId: String): GitHubDeviceLink =
        calls.withAccount(accountId) { client -> refusals { client.githubLink.startDevice().domain() } }

    override suspend fun poll(accountId: String, id: String): GitHubPoll = calls.withAccount(accountId) { client ->
        val answer = try {
            refusals { client.githubLink.pollDevice(id) }
        } catch (error: OstromException) {
            // An attempt the server no longer knows (it ended, or was used) reads as an expired one; a cancelled one as denied.
            return@withAccount when {
                error.status == 404 && error.type == ATTEMPT_GONE -> GitHubPoll.Expired
                error.status == 400 && error.type == LINK_DENIED -> GitHubPoll.Denied
                else -> throw error
            }
        }
        when (answer.status) {
            GitHubDeviceLinkStatus.PENDING -> GitHubPoll.Pending(answer.interval)
            GitHubDeviceLinkStatus.LINKED -> GitHubPoll.Linked((answer.link ?: client.githubLink.get()).domain())
            GitHubDeviceLinkStatus.DENIED -> GitHubPoll.Denied
            // A status a newer server added ends the attempt: starting again is always possible.
            GitHubDeviceLinkStatus.EXPIRED, GitHubDeviceLinkStatus.UNKNOWN -> GitHubPoll.Expired
        }
    }

    override suspend fun unlink(accountId: String) = calls.withAccount(accountId) { it.githubLink.unlink() }

    /** The refusals of the link, by their error key; the server's message is never read nor shown. */
    private suspend fun <T> refusals(call: suspend () -> T): T = try {
        call()
    } catch (error: OstromException) {
        throw refusalOf(error.status, error.type)?.let { AppException.GitHubRefused(it) } ?: error
    }

    private companion object {
        const val ATTEMPT_GONE = "github_link_not_found"
        const val LINK_DENIED = "github_link_denied"
    }
}

/** The refusal a status and error key stand for; null for any other failure (translated as usual). */
internal fun refusalOf(status: Int, type: String?): GitHubRefusal? = when (type) {
    "github_app_not_configured" -> GitHubRefusal.APP_NOT_CONFIGURED
    "github_device_flow_disabled" -> GitHubRefusal.DEVICE_FLOW_DISABLED
    "github_app_misconfigured" -> GitHubRefusal.APP_MISCONFIGURED
    "github_account_in_use" -> GitHubRefusal.ACCOUNT_IN_USE
    "github_unreachable" -> GitHubRefusal.UNREACHABLE
    else -> null
}.takeIf { status in 400..599 }

internal fun ApiGitHubLink.domain() = GitHubLink(
    state = when (state) {
        ApiGitHubLinkState.LINKED -> GitHubLinkState.LINKED
        ApiGitHubLinkState.RECONNECT_REQUIRED -> GitHubLinkState.RECONNECT_REQUIRED
        ApiGitHubLinkState.NOT_LINKED -> GitHubLinkState.NOT_LINKED
        ApiGitHubLinkState.UNKNOWN -> GitHubLinkState.UNKNOWN
    },
    appConfigured = appConfigured,
    login = login,
    linkedAt = linkedAt?.let(RemoteAuthRepository::epochMillis),
)

private fun ApiGitHubDeviceLink.domain() = GitHubDeviceLink(id, userCode, verificationUri, RemoteAuthRepository.epochMillis(expiresAt), interval)
