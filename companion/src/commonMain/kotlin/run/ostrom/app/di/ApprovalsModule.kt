// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/WaitsModule.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.demo.DemoApprovalRepository
import run.ostrom.app.demo.DemoAwareApprovalRepository
import run.ostrom.app.demo.DemoAwareBiometricGate
import run.ostrom.app.demo.DemoCredentialStore
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.NoBiometricGate
import run.ostrom.app.domain.usecases.approvals.AnswerApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.GetApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.LoadApprovalsUseCase
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.infrastructure.repositories.RemoteApprovalRepository
import run.ostrom.app.presentation.navigation.RequestRoute
import run.ostrom.app.presentation.viewmodels.InboxViewModel
import run.ostrom.app.presentation.viewmodels.RequestViewModel

/**
 * Approvals (`gates.v1`): the inbox, the request and the answer after the device's unlock. The platform module binds
 * the [BiometricGate] on the phones; the desktop binds none and gets [NoBiometricGate]. The demo account's calls stay
 * in memory, and its gate always confirms.
 */
val approvalsModule = module {
    single { RemoteApprovalRepository(get()) }
    single<ApprovalRepository> { DemoAwareApprovalRepository(get<RemoteApprovalRepository>(), DemoApprovalRepository(get(), get())) }
    single { DemoAwareBiometricGate(getOrNull<BiometricGate>() ?: NoBiometricGate, get()) }
    // The unlock key is read through DemoCredentialStore, like the token: nothing is read from the device for the demo.
    single { PhoneUnlock(get<DemoCredentialStore>(), get(), get<DemoAwareBiometricGate>(), get()) }
    single { LoadApprovalsUseCase(get(), get()) }
    single { GetApprovalUseCase(get(), get()) }
    single { AnswerApprovalUseCase(get(), get(), get()) }
    viewModel { InboxViewModel(get(), get()) }
    viewModel { (route: RequestRoute) -> RequestViewModel(route, get(), get(), get()) }
}
