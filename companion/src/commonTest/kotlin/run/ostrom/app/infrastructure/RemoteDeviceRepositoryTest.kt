// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/RemoteSessionRepositoryTest.kt (devices); see NOTICE.
package run.ostrom.app.infrastructure

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DeviceFolder
import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.domain.models.HostDevice
import run.ostrom.app.domain.models.ResumableSession
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteDeviceRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The member's devices over the typed client: only the online ones, a device's folders, a folder's conversations. */
class RemoteDeviceRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers["${request.method.value} ${request.url.encodedPath}"]
                    ?: (HttpStatusCode.NotFound to """{"error":{"type":"not_found","message":"m"}}""")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteDeviceRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    @Test
    fun onlyTheDevicesWhoseDaemonIsOnlineAreOffered() = runTest {
        signedIn()
        answers["GET /api/v1/devices"] = HttpStatusCode.OK to """{"items":[
            {"id":"d1","name":"Ada's MacBook","platform":"macos","osVersion":"15.1","current":true,"daemon":{"online":true,"lastSeenAt":"2026-11-14T09:00:00Z"}},
            {"id":"d2","name":"Old PC","daemon":{"online":false,"lastSeenAt":"2026-11-01T09:00:00Z"}},
            {"id":"d3","name":"Never polled"}]}"""
        assertEquals(listOf(HostDevice("d1", "Ada's MacBook", "macos", "15.1", current = true)), repository.onlineDevices("a1"))
    }

    @Test
    fun aDevicesFoldersSayWhetherItsDaemonListsConversations() = runTest {
        signedIn()
        answers["GET /api/v1/devices/d1/folders"] = HttpStatusCode.OK to """{"deviceId":"d1","daemon":{"online":true,"lastSeenAt":"2026-11-14T09:00:00Z",
            "features":["resume.v1"]},"folders":[{"id":"f1","name":"app","label":"~/git/app"},{"id":"f2","name":"web"}]}"""
        answers["GET /api/v1/devices/d2/folders"] = HttpStatusCode.OK to """{"deviceId":"d2","folders":[]}"""
        assertEquals(DeviceFolders(true, listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "web", null)), resumes = true), repository.folders("a1", "d1"))
        assertEquals(DeviceFolders(false, emptyList(), resumes = false), repository.folders("a1", "d2"))
        answers["GET /api/v1/devices/d9/folders"] = HttpStatusCode.Conflict to """{"error":{"type":"device_offline","message":"m"}}"""
        assertEquals("device_offline", assertFailsWith<AppException.Server> { repository.folders("a1", "d9") }.code)
    }

    @Test
    fun aFoldersConversationsArePagedByTheServersCursor() = runTest {
        signedIn()
        answers["GET /api/v1/devices/d1/folders/f1/resumable"] = HttpStatusCode.OK to """{"items":[
            {"id":"0000000a-aaaa-4bbb-8ccc-00000000000a","title":"Fix the build","lastActiveAt":"2026-11-14T09:00:00Z","turns":4},
            {"id":"0000000b-aaaa-4bbb-8ccc-00000000000b","title":"Odd date","lastActiveAt":"yesterday","turns":1}],"next":"cursor-2"}"""
        val page = repository.resumable("a1", "d1", "f1", 20, before = "cursor-1")
        assertEquals("20", requests.last().url.parameters["limit"])
        assertEquals("cursor-1", requests.last().url.parameters["before"])
        assertEquals(ResumableSession("0000000a-aaaa-4bbb-8ccc-00000000000a", "Fix the build", 1_794_646_800_000L, 4), page.sessions.first())
        assertEquals(0L, page.sessions.last().lastActiveAt, "an unreadable date reads as 0")
        assertEquals("cursor-2", page.nextBefore)
    }
}
