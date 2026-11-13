// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion RemoteWaitRepositoryTest (Apache-2.0); see NOTICE.
package run.ostrom.app.infrastructure

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitResolver
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteWaitRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteWaitRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableMapOf<String, String>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request -> requests += request; answer(request) }
        })
    }
    private var answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        val body = bodies[request.url.parameters["resolver"] ?: request.url.encodedPath] ?: bodies[request.url.encodedPath]
        if (body == null) respond("""{"error":{"type":"not_found","message":"m"}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val clients = OstromClientProvider(engine, credentials, accounts, platform)
    private val repository = RemoteWaitRepository(AccountCalls(clients, accounts))

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private fun wait(id: String, status: String = "open", resolver: String? = null, extra: String = "") = """
        {"id":"$id","kind":"question","status":"$status","sessionId":"s1","assignee":"ada","createdAt":"2026-11-13T10:00:00Z",
         "question":{"title":"Which store?","body":"Pick one.","fields":[
            {"id":"store","type":"choice","label":"Store","required":true,"options":["App Store","Google Play"]},
            {"id":"ok","type":"boolean","label":"Sure?"},
            {"id":"sig","type":"signature","label":"Signature"}],
          "actions":[{"id":"done","label":"Send","style":"primary"}],"refs":[{"kind":"url","url":"https://example.org"}]}
         ${if (resolver != null) ""","resolver":"$resolver"""" else ""}$extra}
    """.trimIndent()

    @Test
    fun theOpenWaitsOfThePersonAreAskedAndMapped() = runTest {
        signedIn()
        bodies["/api/v1/waits"] = """{"items":[${wait("w1")}],"next":"c2"}"""
        val page = repository.list("a1", WaitStatus.OPEN)
        val url = requests.single().url
        assertEquals("open", url.parameters["status"])
        assertEquals("true", url.parameters["mine"])
        assertEquals("human", url.parameters["resolver"])
        assertEquals("c2", page.nextBefore)
        val wait = page.waits.single()
        assertEquals("Which store?", wait.title)
        assertEquals(WaitKind.QUESTION, wait.kind)
        assertTrue(wait.open)
        assertEquals(listOf(FieldType.CHOICE, FieldType.CONFIRM, FieldType.UNKNOWN), wait.fields.map { it.type })
        assertEquals(listOf("App Store", "Google Play"), wait.fields.first().options.map { it.label })
        assertEquals(1_794_564_000_000L, wait.createdAt)
        assertEquals("ada", wait.assigneeId)
        assertEquals(WaitResolver.HUMAN, wait.resolver)
    }

    @Test
    fun theNextPageIsAskedFromItsCursor() = runTest {
        signedIn()
        bodies["/api/v1/waits"] = """{"items":[]}"""
        val page = repository.list("a1", WaitStatus.RESOLVED, before = "c2")
        assertEquals("c2", requests.single().url.parameters["before"])
        assertEquals("resolved", requests.single().url.parameters["status"])
        assertNull(page.nextBefore)
    }

    @Test
    fun anAnswerSendsTheActionAndValuesAndReadsTheAnsweredWait() = runTest {
        signedIn()
        bodies["/api/v1/waits/w1/answer"] = wait("w1", status = "resolved", extra = ""","resolvedAt":"2026-11-13T10:05:00Z","answer":{"action":"done","values":{"store":"App Store"},"author":"ada","answeredAt":"2026-11-13T10:05:00Z"}""")
        val answered = repository.answer("a1", "w1", "done", mapOf("store" to JsonPrimitive("App Store")))
        val body = (requests.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("\"action\":\"done\"" in body && "\"store\":\"App Store\"" in body, body)
        assertFalse(answered.open)
        assertEquals("done", answered.answer?.action)
        assertEquals(1_794_564_300_000L, answered.answer?.answeredAt)
    }

    @Test
    fun aWaitAnsweredFirstElsewhereIsAConflict() = runTest {
        signedIn()
        answer = { respond("""{"error":{"type":"wait_closed","message":"m"}}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json")) }
        val error = assertFailsWith<AppException.Server> { repository.answer("a1", "w1", "done", emptyMap()) }
        assertEquals(409, error.statusCode)
        assertEquals("wait_closed", error.code)
    }

    @Test
    fun theWaitsInProgressAreTheTimersAndWebhooksSoonestFirst() = runTest {
        signedIn()
        bodies["time"] = """{"items":[${wait("t1", resolver = "time", extra = ""","dueAt":"2026-11-13T12:00:00Z"""")}]}"""
        bodies["event"] = """{"items":[${wait("e1", resolver = "event", extra = ""","timeoutAt":"2026-11-13T11:00:00Z","eventSource":"webhook"""")}]}"""
        val waits = repository.inProgress("a1")
        assertEquals(listOf("e1", "t1"), waits.map { it.id })
        assertEquals(listOf(WaitResolver.EVENT, WaitResolver.TIME), waits.map { it.resolver })
        assertEquals(listOf("time", "event"), requests.map { it.url.parameters["resolver"] })
    }

    @Test
    fun anUnreadableInstantReadsAsUnknown() = runTest {
        signedIn()
        bodies["/api/v1/waits"] = """{"items":[${wait("w1", extra = ""","dueAt":"tomorrow"""").replace("2026-11-13T10:00:00Z", "soon")}]}"""
        val wait = repository.list("a1", WaitStatus.OPEN).waits.single()
        assertEquals(0L, wait.createdAt)
        assertNull(wait.dueAt)
    }
}
