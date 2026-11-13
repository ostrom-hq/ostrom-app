// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../demo/DemoModule.kt; see NOTICE.
package run.ostrom.app.demo

import org.koin.dsl.module
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.infrastructure.repositories.CachedFeaturesRepository
import run.ostrom.app.infrastructure.repositories.RemoteAuthRepository
import run.ostrom.app.infrastructure.repositories.RemoteWaitRepository
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository

/**
 * The built-in demo: binds each repository the app uses to a router that answers the demo account from memory
 * ([DemoMode]) and every other account from its server (the `Remote…` implementations). The platform module binds
 * the real [CredentialStore]; the app reads secrets through [DemoCredentialStore].
 */
val demoModule = module {
    single { DemoMode(get()) }
    single { DemoCredentialStore(get<CredentialStore>()) }
    single { StartDemoUseCase(get()) }
    single { DemoAuthRepository(get()) }
    single<AccountRepository> { DemoAccountRepository(get<SettingsAccountRepository>(), get()) }
    single<AuthRepository> { DemoAwareAuthRepository(get<RemoteAuthRepository>(), get<DemoAuthRepository>()) }
    single<FeaturesRepository> { DemoAwareFeaturesRepository(get<CachedFeaturesRepository>(), DemoFeaturesRepository(get())) }
    single<WaitRepository> { DemoAwareWaitRepository(get<RemoteWaitRepository>(), DemoWaitRepository(get(), get())) }
}
