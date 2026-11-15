// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../di/TransferModule.kt; see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.transfers.LoadRecipientsUseCase
import run.ostrom.app.domain.usecases.transfers.TransferAvailabilityUseCase
import run.ostrom.app.domain.usecases.transfers.TransferListUseCase
import run.ostrom.app.domain.usecases.transfers.TransferSessionUseCase
import run.ostrom.app.infrastructure.repositories.RemoteTransferRepository
import run.ostrom.app.presentation.navigation.TransferRoute
import run.ostrom.app.presentation.navigation.TransfersRoute
import run.ostrom.app.presentation.viewmodels.TransferViewModel
import run.ostrom.app.presentation.viewmodels.TransfersViewModel

/** Session transfers (`runners.cloud.transfer.v1`): the repository, its use cases and the two view models. */
val transferModule = module {
    // Bound by its class: the demo module binds the interface to a router that answers the demo account from memory.
    single { RemoteTransferRepository(get()) }
    single { TransferAvailabilityUseCase(get(), get()) }
    single { LoadRecipientsUseCase(get(), get(), get()) }
    single { TransferSessionUseCase(get(), get(), get(), get()) }
    single { TransferListUseCase(get(), get(), get()) }
    viewModel { (route: TransferRoute) -> TransferViewModel(route, get(), get(), get(), get()) }
    viewModel { (route: TransfersRoute) -> TransfersViewModel(route, get(), get()) }
}
