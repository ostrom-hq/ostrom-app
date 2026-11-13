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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import run.ostrom.app.di.appModules
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore

object TestPlatform : PlatformInfo {
    override val platform = "Test"
    override val deviceModel = "Test"
    override val appVersion = "9.9.9"
}

/**
 * A fake server: [routes] answers `METHOD /path` with a JSON body (status 200, or the one in [statuses]), anything else
 * with 404. A route may name query parameters (`GET /api/v1/waits?status=open&resolver=time`): it answers only a request
 * that has those values, and wins over the route of the bare path. [failures] answers a route with a status and an error
 * type instead. [bodies] records the last body each `METHOD /path` was sent, [sent] all of them in order.
 */
class FakeServer(
    val routes: MutableMap<String, String> = mutableMapOf(),
    val statuses: MutableMap<String, Int> = mutableMapOf(),
) : HttpClientEngineFactory<MockEngineConfig> {
    val asked = mutableListOf<String>()
    val failures = mutableMapOf<String, Pair<Int, String>>()
    val bodies = mutableMapOf<String, String>()
    val sent = mutableMapOf<String, MutableList<String>>()
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
        (request.body as? OutgoingContent.ByteArrayContent)?.let {
            val text = it.bytes().decodeToString()
            bodies[key] = text
            sent.getOrPut(key) { mutableListOf() } += text
        }
        failures[key]?.let { (status, type) ->
            return@synchronized respond("""{"error":{"type":"$type","message":"m"}}""", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val withQuery = routes.entries.firstOrNull { (route, _) ->
            route.startsWith("$key?") && route.substringAfter('?').split('&').all { pair -> request.url.parameters[pair.substringBefore('=')] == pair.substringAfter('=') }
        }?.value
        (withQuery ?: routes[key])?.let { respond(it, HttpStatusCode.fromValue(statuses[key] ?: 200), headersOf(HttpHeaders.ContentType, "application/json")) }
            ?: respond("""{"error":{"type":"not_found","message":"m"}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

/** The links the app asked to open, instead of a browser. */
class RecordingUriHandler(var fails: Boolean = false) : UriHandler {
    val opened = mutableListOf<String>()
    override fun openUri(uri: String) {
        // A system without a browser, or one that refuses the link.
        check(!fails) { "No browser" }
        opened += uri
    }
}

/**
 * The whole app (App(), its screens, navigation and modules) on a fresh device of the desktop platform (device-flow
 * sign-in) talking to [server], in a [width] × [height] window; [before] prepares the device. [biometrics] stands in
 * for a phone's gate (the desktop has none).
 */
@OptIn(ExperimentalTestApi::class)
fun testApp(
    server: FakeServer,
    width: Int = 1100,
    height: Int = 800,
    credentials: CredentialStore = MemoryCredentialStore(),
    settings: Settings = MapSettings(),
    links: RecordingUriHandler = RecordingUriHandler(),
    biometrics: BiometricGate? = null,
    block: DesktopComposeUiTest.(Koin) -> Unit,
) {
    val koin = startKoin {
        modules(appModules(module {
            single<Settings> { settings }
            single<CredentialStore> { credentials }
            single<HttpClientEngineFactory<*>> { server }
            single<PlatformInfo> { TestPlatform }
            single<SecureRandom> { SecureRandom { ByteArray(it) { index -> index.toByte() } } }
            if (biometrics != null) single<BiometricGate> { biometrics }
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
