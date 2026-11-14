// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../di/WaitsModule.kt; see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.waits.AnswerWaitUseCase
import run.ostrom.app.domain.usecases.waits.FollowWaitsUseCase
import run.ostrom.app.domain.usecases.waits.LoadInProgressWaitsUseCase
import run.ostrom.app.domain.usecases.waits.LoadWaitsUseCase
import run.ostrom.app.domain.repositories.EventRepository
import run.ostrom.app.infrastructure.repositories.RemoteEventRepository
import run.ostrom.app.infrastructure.repositories.RemoteWaitRepository
import run.ostrom.app.presentation.viewmodels.WaitsViewModel

/** Waits (`waits.v1`): what agents need from the person, on the Waiting tab and counted by its badge. */
val waitsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's calls in memory.
    single { RemoteWaitRepository(get()) }
    single { LoadWaitsUseCase(get(), get(), get()) }
    single { AnswerWaitUseCase(get(), get()) }
    single<EventRepository> { RemoteEventRepository(get()) }
    single { FollowWaitsUseCase(get(), get(), get()) }
    single { LoadInProgressWaitsUseCase(get(), get(), get()) }
    viewModel { WaitsViewModel(get(), get(), get(), get(), get()) }
}
