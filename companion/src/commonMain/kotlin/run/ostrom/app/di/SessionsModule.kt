// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.sessions.FollowSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.GetSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadSessionsUseCase
import run.ostrom.app.domain.usecases.todos.FollowTodoUseCase
import run.ostrom.app.infrastructure.repositories.RemoteSessionRepository
import run.ostrom.app.infrastructure.repositories.RemoteTodoRepository
import run.ostrom.app.presentation.navigation.SessionRoute
import run.ostrom.app.presentation.viewmodels.SessionViewModel
import run.ostrom.app.presentation.viewmodels.SessionsViewModel

/** The sessions (`sessions.v1`) and their todos (`todos.v1`): the Sessions tab and a session's record. */
val sessionsModule = module {
    // Bound by their class: the demo module binds the interfaces to routers that keep the demo account's in memory.
    single { RemoteSessionRepository(get()) }
    single { RemoteTodoRepository(get()) }
    single { LoadSessionsUseCase(get(), get(), get()) }
    single { GetSessionUseCase(get(), get(), get()) }
    single { FollowSessionsUseCase(get(), get(), get()) }
    single { FollowTodoUseCase(get(), get(), get(), get()) }
    viewModel { SessionsViewModel(get(), get(), get()) }
    viewModel { (route: SessionRoute) -> SessionViewModel(route, get(), get()) }
}
