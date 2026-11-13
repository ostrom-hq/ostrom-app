// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import run.ostrom.app.demo.DemoCredentialStore
import run.ostrom.app.domain.repositories.PreferencesRepository
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.BrowserSignInUseCase
import run.ostrom.app.domain.usecases.auth.CompleteSignInUseCase
import run.ostrom.app.domain.usecases.auth.DeviceSignInUseCase
import run.ostrom.app.domain.usecases.auth.InspectServerUseCase
import run.ostrom.app.domain.usecases.auth.OrganizationUseCases
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.auth.SignOutUseCase
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.CachedFeaturesRepository
import run.ostrom.app.infrastructure.repositories.RemoteAuthRepository
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.infrastructure.settings.SettingsPreferencesRepository
import run.ostrom.app.infrastructure.system.RandomIdGenerator
import run.ostrom.app.infrastructure.system.SystemClock

/**
 * Implementations; the platform module adds Settings, the CredentialStore, the HTTP engine, PlatformInfo, SecureRandom
 * and, on a phone, the BrowserSignIn. The repositories are bound by their own class here: [run.ostrom.app.demo.demoModule]
 * binds their interfaces to routers that keep the demo account's calls in memory.
 */
val infrastructureModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<Clock> { SystemClock }
    single<IdGenerator> { RandomIdGenerator }
    single { OstromClientProvider(get(), get<DemoCredentialStore>(), get(), get()) }
    single { SettingsAccountRepository(get()) }
    single<PreferencesRepository> { SettingsPreferencesRepository(get()) }
    single { AccountCalls(get(), get()) }
    single { RemoteAuthRepository(get(), get(), get(), get()) }
    single { CachedFeaturesRepository(get(), get()) }
}

/** The account checks go through [DemoCredentialStore]: the demo account has no token, and none is read or deleted for it. */
val domainModule = module {
    single { InspectServerUseCase(get()) }
    single { CompleteSignInUseCase(get(), get(), get<DemoCredentialStore>(), get()) }
    // Resolved only where a BrowserSignIn exists (the phones' platform modules).
    single { BrowserSignInUseCase(get(), get(), get(), get()) }
    single { DeviceSignInUseCase(get(), get(), get()) }
    single { SignOutUseCase(get(), get(), get<DemoCredentialStore>()) }
    single { RefreshAccountUseCase(get(), get(), get<DemoCredentialStore>(), get()) }
    single { AccountGuard(get()) }
    single { OrganizationUseCases(get(), get(), get()) }
}
