// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/github/GitHubLinkUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.github

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubDeviceLink
import run.ostrom.app.domain.models.GitHubLink
import run.ostrom.app.domain.models.GitHubPoll
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.GitHubLinkRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** What the GitHub screen shows: the member's link, or null when the server does not offer GitHub links (`github.link.v1`). */
class LoadGitHubLinkUseCase(
    private val repository: GitHubLinkRepository,
    private val features: FeaturesRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String): GitHubLink? {
        accounts.find(accountId)?.takeIf { it.signedIn } ?: throw AppException.Unauthorized()
        return guard(accountId) {
            // Read again from the server: a link is offered or withdrawn with the instance's GitHub App.
            if (Features.GITHUB_LINK in features.capabilities(accountId, refresh = true).features) repository.link(accountId) else null
        }
    }
}

/** Starts GitHub's device flow; the server refuses (`409`) when the instance's GitHub App is missing or forbids it. */
class StartGitHubLinkUseCase(private val repository: GitHubLinkRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): GitHubDeviceLink = guard(accountId) { repository.start(accountId) }
}

/** One poll of the device flow; the screen waits the interval between two. */
class PollGitHubLinkUseCase(private val repository: GitHubLinkRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, id: String): GitHubPoll = guard(accountId) { repository.poll(accountId, id) }
}

/** Removes the link and revokes its token at GitHub (the server does both). */
class UnlinkGitHubUseCase(private val repository: GitHubLinkRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String) = guard(accountId) { repository.unlink(accountId) }
}
