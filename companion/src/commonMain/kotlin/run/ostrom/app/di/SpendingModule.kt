// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.spending.LoadActivityUseCase
import run.ostrom.app.domain.usecases.spending.LoadSpendingUseCase
import run.ostrom.app.infrastructure.repositories.RemoteActivityRepository
import run.ostrom.app.infrastructure.repositories.RemoteSpendingRepository
import run.ostrom.app.presentation.viewmodels.ActivityViewModel
import run.ostrom.app.presentation.viewmodels.SpendingViewModel

/** Spending (`budgets.v1`) and the activity feed. */
val spendingModule = module {
    // Bound by their class: the demo module binds the interfaces to routers that keep the demo account's calls in memory.
    single { RemoteSpendingRepository(get()) }
    single { RemoteActivityRepository(get()) }
    single { LoadSpendingUseCase(get(), get(), get()) }
    single { LoadActivityUseCase(get(), get()) }
    viewModel { SpendingViewModel(get(), get()) }
    viewModel { ActivityViewModel(get(), get()) }
}
