// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion jvmTest DemoModeScreenTest.kt helpers (Apache-2.0); see NOTICE.
package run.ostrom.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import run.ostrom.app.di.appModules
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore

object TestPlatform : PlatformInfo {
    override val platform = "Test"
    override val deviceModel = "Test"
    override val appVersion = "9.9.9"
}

/** A fake server: [routes] answers `METHOD /path` with a JSON body (status 200), anything else with 404. */
class FakeServer(val routes: MutableMap<String, String> = mutableMapOf()) : HttpClientEngineFactory<MockEngineConfig> {
    val asked = mutableListOf<String>()
    var clients = 0

    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine {
        clients++
        return MockEngine(MockEngineConfig().apply {
            addHandler { request -> answer(request) }
        })
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.answer(request: HttpRequestData) = synchronized(this@FakeServer) {
        val key = "${request.method.value} ${request.url.encodedPath}"
        asked += key
        routes[key]?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            ?: respond("""{"error":{"type":"not_found","message":"m"}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

/** The links the app asked to open, instead of a browser. */
class RecordingUriHandler : UriHandler {
    val opened = mutableListOf<String>()
    override fun openUri(uri: String) {
        opened += uri
    }
}

/**
 * The whole app (App(), its screens, navigation and modules) on a fresh device of the desktop platform (device-flow
 * sign-in) talking to [server], in a [width] × [height] window; [before] prepares the device.
 */
@OptIn(ExperimentalTestApi::class)
fun testApp(
    server: FakeServer,
    width: Int = 1100,
    height: Int = 800,
    credentials: CredentialStore = MemoryCredentialStore(),
    settings: Settings = MapSettings(),
    links: RecordingUriHandler = RecordingUriHandler(),
    block: DesktopComposeUiTest.(Koin) -> Unit,
) {
    val koin = startKoin {
        modules(appModules(module {
            single<Settings> { settings }
            single<CredentialStore> { credentials }
            single<HttpClientEngineFactory<*>> { server }
            single<PlatformInfo> { TestPlatform }
            single<SecureRandom> { SecureRandom { ByteArray(it) { index -> index.toByte() } } }
        }))
    }.koin
    try {
        runDesktopComposeUiTest(width, height) {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalUriHandler provides links) {
                    org.koin.compose.KoinContext(koin) { App() }
                }
            }
            settle()
            block(koin)
            settle()
        }
    } finally {
        stopKoin()
    }
}

@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.settle() {
    repeat(3) { mainClock.advanceTimeBy(1_000); waitForIdle() }
}
