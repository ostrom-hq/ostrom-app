// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../di/GitHubModule.kt; see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.github.LoadGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.PollGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.StartGitHubLinkUseCase
import run.ostrom.app.domain.usecases.github.UnlinkGitHubUseCase
import run.ostrom.app.infrastructure.repositories.RemoteGitHubLinkRepository
import run.ostrom.app.presentation.navigation.GitHubLinkRoute
import run.ostrom.app.presentation.viewmodels.GitHubLinkViewModel

/** The member's GitHub link (`github.link.v1`): which account is linked, and linking one with GitHub's device flow. */
val githubModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteGitHubLinkRepository(get()) }
    single { LoadGitHubLinkUseCase(get(), get(), get(), get()) }
    single { StartGitHubLinkUseCase(get(), get()) }
    single { PollGitHubLinkUseCase(get(), get()) }
    single { UnlinkGitHubUseCase(get(), get()) }
    viewModel { (route: GitHubLinkRoute) -> GitHubLinkViewModel(route, get(), get(), get(), get()) }
}
