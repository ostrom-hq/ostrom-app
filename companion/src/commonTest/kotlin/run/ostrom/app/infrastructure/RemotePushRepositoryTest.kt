// SPDX-License-Identifier: Apache-2.0
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemotePushRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemotePushRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                if (request.method == HttpMethod.Delete) {
                    respond("", HttpStatusCode.NoContent)
                } else {
                    respond(
                        """{"id":"d1","provider":"fcm","platform":"android","locale":"fr-FR","createdAt":"2026-11-14T00:00:00Z"}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Android"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemotePushRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    @Test
    fun thePhoneIsRegisteredWithItsTokenAndKeyThenDeletedBeforeASignOut() = runTest {
        accounts.save(account())
        credentials.write("a1", "osm_token")
        repository.register("a1", "fcm-token-0123456789", "spki", "android", "fr-FR")
        val register = requests.single()
        assertEquals(HttpMethod.Post, register.method)
        assertEquals("/api/v1/push-devices", register.url.encodedPath)
        val body = Json.parseToJsonElement((register.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
        assertEquals(
            JsonObject(mapOf(
                "pushToken" to JsonPrimitive("fcm-token-0123456789"),
                "provider" to JsonPrimitive("fcm"),
                "platform" to JsonPrimitive("android"),
                "locale" to JsonPrimitive("fr-FR"),
                "publicKey" to JsonPrimitive("spki"),
            )),
            body,
        )

        repository.clear("a1")
        assertEquals(HttpMethod.Delete, requests.last().method)
        assertEquals("/api/v1/push-devices/d1", requests.last().url.encodedPath)

        // Nothing registered (any more) in this process: nothing to delete.
        repository.clear("a1")
        assertEquals(2, requests.size)
        assertTrue(requests.none { it.url.encodedPath.endsWith("/null") })
    }
}
