// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion RemoteWaitRepositoryTest (Apache-2.0); see NOTICE.
package run.ostrom.app.infrastructure

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.QuietHours
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteNotificationPreferencesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteNotificationPreferencesRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()
    private var answer = """{"requests":true,"decisions":false,"spending":true}"""

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteNotificationPreferencesRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    @Test
    fun thePreferencesAreReadWithoutQuietHours() = runTest {
        signedIn()
        val read = repository.preferences("a1")
        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals("/api/v1/me/notifications", requests.single().url.encodedPath)
        assertEquals(NotificationPreferences(requests = true, decisions = false, spending = true), read)
        assertNull(read.quietHours)
    }

    @Test
    fun theyAreSavedWholeAndReadBackAsTheServerHoldsThem() = runTest {
        signedIn()
        answer = """{"requests":false,"decisions":true,"spending":true,"quietHours":{"start":"22:00","end":"07:00","timeZone":"Europe/Paris"}}"""
        val quiet = QuietHours("22:00", "07:00", "Europe/Paris")
        val saved = repository.save("a1", NotificationPreferences(requests = false, decisions = true, spending = true, quietHours = quiet))
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("\"requests\":false" in body && "\"timeZone\":\"Europe/Paris\"" in body, body)
        assertEquals(quiet, saved.quietHours)
    }
}
