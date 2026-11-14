// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/RemoteArtifactRepositoryTest.kt; see NOTICE.
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
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteArtifactRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteArtifactRepositoryTest {
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers[request.url.encodedPath] ?: (HttpStatusCode.NotFound to """{"error":{"type":"not_found","message":"m"}}""")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteArtifactRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    private fun artifact(title: String = "Plan") =
        """{"id":"$id","title":"$title","owner":"ada","access":"team","relation":"owner","latestVersion":2,"url":"https://app.example.org/artifacts/$id",
           "createdAt":"2026-11-12T10:00:00Z","updatedAt":"2026-11-13T10:00:00Z"}"""

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    @Test
    fun aPageIsAskedFromItsCursorAndMapped() = runTest {
        signedIn()
        answers["/api/v1/artifacts"] = HttpStatusCode.OK to """{"items":[${artifact()}],"next":"c3"}"""
        val page = repository.artifacts("a1", before = "c2")
        assertEquals("c2", requests.single().url.parameters["before"])
        assertEquals("50", requests.single().url.parameters["limit"])
        assertEquals("c3", page.next)
        val first = page.artifacts.single()
        assertEquals("Plan", first.title)
        assertEquals("owner", first.relation)
        assertEquals(2, first.latestVersion)
        assertEquals(1_794_564_000_000L, first.updatedAt)
        assertEquals("https://app.example.org/artifacts/$id", first.url)
    }

    @Test
    fun anArtifactAndItsVersionsNewestFirst() = runTest {
        signedIn()
        answers["/api/v1/artifacts/$id"] = HttpStatusCode.OK to artifact("Read")
        answers["/api/v1/artifacts/$id/versions"] = HttpStatusCode.OK to """{"items":[
            {"number":1,"files":["index.html"],"entry":"index.html","createdAt":"2026-11-12T10:00:00Z","createdBy":"ada"},
            {"number":2,"files":["index.html","a.png"],"entry":"index.html","createdAt":"not a time","createdBy":"bob"}]}"""
        assertEquals("Read", repository.artifact("a1", id).title)
        assertEquals(
            listOf(ArtifactVersion(2, null, "bob", 2), ArtifactVersion(1, 1_794_477_600_000L, "ada", 1)),
            repository.versions("a1", id),
        )
    }

    @Test
    fun aTicketIsAskedForItsVersionAndAServerWithoutPagesOffersNone() = runTest {
        signedIn()
        answers["/api/v1/artifacts/$id/tickets"] = HttpStatusCode.Created to
            """{"artifactId":"$id","version":2,"url":"https://artifacts.example.org/a/$id/tk/index.html","contentUrl":"https://artifacts.example.org/a/$id/tk/index.html","expiresAt":"2026-11-13T18:00:00Z"}"""
        val ticket = repository.ticket("a1", id, 2)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertTrue((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().contains("\"version\":2"))
        assertEquals("https://artifacts.example.org/a/$id/tk/index.html", ticket.contentUrl)
        assertEquals(2, ticket.version)
        assertEquals(1_794_592_800_000L, ticket.expiresAt)
        assertNull(ticket.inlineHtml)

        answers["/api/v1/artifacts/$id/tickets"] = HttpStatusCode.ServiceUnavailable to """{"error":{"type":"unavailable","message":"m"}}"""
        assertEquals(Features.ARTIFACTS, assertFailsWith<AppException.Unsupported> { repository.ticket("a1", id, null) }.feature)
        answers["/api/v1/artifacts/$id/tickets"] = HttpStatusCode.TooManyRequests to """{"error":{"type":"rate_limited","message":"m"}}"""
        assertFailsWith<AppException.RateLimited> { repository.ticket("a1", id, null) }
        answers["/api/v1/artifacts/$id/tickets"] = HttpStatusCode.ServiceUnavailable to """{"error":{"type":"overloaded","message":"m"}}"""
        assertEquals(503, assertFailsWith<AppException.Server> { repository.ticket("a1", id, null) }.statusCode)
    }

    @Test
    fun aHiddenArtifactIsGone() = runTest {
        signedIn()
        assertFailsWith<AppException.NotFound> { repository.artifact("a1", id) }
        assertFailsWith<AppException.NotFound> { repository.ticket("a1", id, null) }
    }
}
