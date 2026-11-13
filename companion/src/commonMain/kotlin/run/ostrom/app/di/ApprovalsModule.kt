// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion di/WaitsModule.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.dsl.module
import run.ostrom.app.demo.DemoCredentialStore
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.NoBiometricGate
import run.ostrom.app.domain.usecases.approvals.AnswerApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.GetApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.LoadApprovalsUseCase
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.infrastructure.repositories.RemoteApprovalRepository

/**
 * Approvals (`gates.v1`): the inbox, the request and the answer after the device's unlock. The platform module binds
 * the [BiometricGate] on the phones; the desktop binds none and gets [NoBiometricGate].
 */
val approvalsModule = module {
    single { RemoteApprovalRepository(get()) }
    single<ApprovalRepository> { get<RemoteApprovalRepository>() }
    // The unlock key is read through DemoCredentialStore, like the token: nothing is read from the device for the demo.
    single { PhoneUnlock(get<DemoCredentialStore>(), get(), getOrNull<BiometricGate>() ?: NoBiometricGate, get()) }
    single { LoadApprovalsUseCase(get(), get()) }
    single { GetApprovalUseCase(get(), get()) }
    single { AnswerApprovalUseCase(get(), get(), get()) }
}
