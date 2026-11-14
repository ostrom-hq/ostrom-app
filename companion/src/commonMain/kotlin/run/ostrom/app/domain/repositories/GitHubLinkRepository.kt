// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/GitHubLinkRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.GitHubDeviceLink
import run.ostrom.app.domain.models.GitHubLink
import run.ostrom.app.domain.models.GitHubPoll

/**
 * The member's GitHub link on an account's server (`github.link.v1`), linked with GitHub's device flow as
 * the CLI does. The token exchange happens on the server: the app never sees a GitHub token. Failures are
 * [run.ostrom.app.domain.models.AppException]s.
 */
interface GitHubLinkRepository {
    suspend fun link(accountId: String): GitHubLink

    /** Starts the device flow; a refusal of the server is an [run.ostrom.app.domain.models.AppException.GitHubRefused]. */
    suspend fun start(accountId: String): GitHubDeviceLink

    suspend fun poll(accountId: String, id: String): GitHubPoll

    /** Removes the link; also when there is none. */
    suspend fun unlink(accountId: String)
}
