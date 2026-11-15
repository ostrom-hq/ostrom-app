// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.infrastructure.repositories.RemoteCloudSessionRepository
import run.ostrom.app.presentation.navigation.CloudSessionRoute
import run.ostrom.app.presentation.navigation.NewCloudSessionRoute
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.CloudSessionsViewModel
import run.ostrom.app.presentation.viewmodels.NewCloudSessionViewModel

/** The person's cloud sessions (`runners.cloud.v1`): their list in the Sessions tab, a session's record, the new session form. */
val cloudSessionsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteCloudSessionRepository(get()) }
    single { CloudSessionAccess(get(), get(), get()) }
    single { LoadCloudSessionsUseCase(get()) }
    single { GetCloudSessionUseCase(get()) }
    single { ControlCloudSessionUseCase(get()) }
    single { LoadCloudSessionOptionsUseCase(get()) }
    single { CreateCloudSessionUseCase(get()) }
    single { SetCloudSessionGitHubAccessUseCase(get()) }
    viewModel { CloudSessionsViewModel(get(), get()) }
    viewModel { (route: CloudSessionRoute) -> CloudSessionViewModel(route, get(), get(), get(), get()) }
    viewModel { (route: NewCloudSessionRoute) -> NewCloudSessionViewModel(route, get(), get(), get()) }
}
