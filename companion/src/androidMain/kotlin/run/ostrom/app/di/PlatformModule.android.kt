// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/androidMain/.../di/PlatformModule.android.kt; see NOTICE.
package run.ostrom.app.di

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.auth.AndroidBrowserSignIn
import run.ostrom.app.infrastructure.credentials.KeystoreCredentialStore
import run.ostrom.app.infrastructure.system.AndroidPlatformInfo
import run.ostrom.app.infrastructure.system.JavaSecureRandom

/** Android: tokens encrypted by an Android Keystore key, preferences excluded from backups, OkHttp, Custom Tabs sign-in. */
fun platformModule(context: Context) = module {
    single<Settings> { SharedPreferencesSettings(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)) }
    single<CredentialStore> { KeystoreCredentialStore(context.getSharedPreferences(KeystoreCredentialStore.FILE, Context.MODE_PRIVATE)) }
    single<HttpClientEngineFactory<*>> { OkHttp }
    single<PlatformInfo> { AndroidPlatformInfo }
    single<SecureRandom> { JavaSecureRandom }
    single<BrowserSignIn> { AndroidBrowserSignIn(context) }
}

/** The app's preferences file (accounts metadata and theme; no secret), excluded from backups. */
const val PREFERENCES = "ostrom"
