// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.usecases.receipts.CheckReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.LoadReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.TrustedReceiptKeysUseCase
import run.ostrom.app.infrastructure.credentials.SecureReceiptKeyPinRepository
import run.ostrom.app.infrastructure.repositories.RemoteReceiptRepository
import run.ostrom.app.presentation.viewmodels.HistoryViewModel

/** The history of signed receipts (`receipts.v1`), verified on the device. */
val receiptsModule = module {
    // Bound by their class: the demo module binds the interfaces to routers that keep the demo account's in memory.
    single { RemoteReceiptRepository(get()) }
    // The platform's secure store itself: the pins are not an account's secrets.
    single { SecureReceiptKeyPinRepository(get<CredentialStore>()) }
    single { LoadReceiptsUseCase(get(), get(), get()) }
    single { TrustedReceiptKeysUseCase(get(), get(), get(), get()) }
    single { CheckReceiptsUseCase() }
    viewModel { HistoryViewModel(get(), get(), get(), get()) }
}
