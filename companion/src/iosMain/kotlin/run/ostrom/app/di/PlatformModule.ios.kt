// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/iosMain/.../di/PlatformModule.ios.kt; see NOTICE.
package run.ostrom.app.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.auth.IosBrowserSignIn
import run.ostrom.app.infrastructure.biometrics.IosBiometricGate
import run.ostrom.app.infrastructure.credentials.KeychainCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.infrastructure.system.IosPlatformInfo
import run.ostrom.app.infrastructure.system.IosSecureRandom

/** iOS: the Keychain, the standard user defaults (metadata only), the Darwin HTTP engine, the system's sign-in sheet, Face ID. */
fun platformModule() = module {
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    // No accounts in the settings: a first launch, or a reinstall that wiped them, so Keychain items are orphans.
    single<CredentialStore> { KeychainCredentialStore(purgeOrphans = !get<Settings>().hasKey(SettingsAccountRepository.ACCOUNTS_KEY)) }
    single<HttpClientEngineFactory<*>> { Darwin }
    single<PlatformInfo> { IosPlatformInfo }
    single<SecureRandom> { IosSecureRandom }
    single<BrowserSignIn> { IosBrowserSignIn() }
    single<BiometricGate> { IosBiometricGate() }
}
