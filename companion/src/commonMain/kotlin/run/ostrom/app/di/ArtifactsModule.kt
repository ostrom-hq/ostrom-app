// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt and di/PresentationModule.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.artifacts.GetArtifactUseCase
import run.ostrom.app.domain.usecases.artifacts.LoadArtifactsUseCase
import run.ostrom.app.domain.usecases.artifacts.OpenArtifactUseCase
import run.ostrom.app.infrastructure.repositories.RemoteArtifactRepository
import run.ostrom.app.presentation.components.embedsArtifacts
import run.ostrom.app.presentation.navigation.ArtifactRoute
import run.ostrom.app.presentation.viewmodels.ArtifactViewModel
import run.ostrom.app.presentation.viewmodels.ArtifactsViewModel

/** The artifacts (`artifacts.v1`): the gallery and the viewer. */
val artifactsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteArtifactRepository(get()) }
    single { LoadArtifactsUseCase(get(), get(), get()) }
    single { GetArtifactUseCase(get(), get()) }
    single { OpenArtifactUseCase(get(), get(), get()) }
    viewModel { ArtifactsViewModel(get(), get()) }
    viewModel { (route: ArtifactRoute) -> ArtifactViewModel(route, get(), get(), embedsArtifacts) }
}
