// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/jvmMain/.../di/PlatformModule.jvm.kt; see NOTICE.
package run.ostrom.app.di

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.java.Java
import java.util.prefs.Preferences
import org.koin.dsl.module
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.credentials.KeyringCredentialStore
import run.ostrom.app.infrastructure.system.DesktopPlatformInfo
import run.ostrom.app.infrastructure.system.JavaSecureRandom

/**
 * Desktop: the OS keyring (Keychain, Credential Manager, Secret Service), Java preferences and the JDK HTTP client.
 * No BrowserSignIn: a computer signs in with the device flow of `ostrom login` (the server only sends a browser back
 * to the phones' `ostrom://auth/callback`).
 */
fun platformModule() = module {
    single<Settings> { PreferencesSettings(Preferences.userRoot().node("run/ostrom/app")) }
    single<CredentialStore> { KeyringCredentialStore.open() }
    single<HttpClientEngineFactory<*>> { Java }
    single<PlatformInfo> { DesktopPlatformInfo }
    single<SecureRandom> { JavaSecureRandom }
}
