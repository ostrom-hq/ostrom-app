// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/api/contract/receipts.test.ts and src/repositories/PinnedReceiptsRepository.test.ts.
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
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.KeyHandover
import run.ostrom.app.domain.models.ReceiptCheckNode
import run.ostrom.app.domain.models.RunOrigin
import run.ostrom.app.domain.models.ServedReceiptKey
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.credentials.SecureReceiptKeyPinRepository
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteReceiptRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteReceiptRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableMapOf<String, String>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val body = bodies[request.url.encodedPath]
                if (body == null) respond("""{"error":{"type":"not_found","message":"m"}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val calls = AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts)

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private val workflow = """
        {"id":"rcpt_w","subject":{"kind":"workflow","id":"release@p1","digest":"d"},"initiator":"charles","rules":[],
         "decisions":[{"approvalId":"g1","by":"ada","at":"2026-11-13T10:00:00Z","result":"approved","selfApproved":true}],
         "nodes":[{"id":"n1","workflowId":"w","kind":"check","label":"Build","outcome":"passed","attestation":"ci"},
                  {"id":"n2","workflowId":"w","kind":"check","label":"Lint","outcome":"failed"},
                  {"id":"n3","workflowId":"w","kind":"check","label":"Fmt","outcome":"passed","attestation":"quantum"},
                  {"id":"n4","workflowId":"w","kind":"notify","label":"Tell","outcome":"sent"}],
         "createdAt":"2026-11-13T09:00:00.000Z","signature":"ed25519;kid=k;sha256=s;sig=AA==","newerField":{"x":1}}
    """.trimIndent()

    @Test
    fun aPageIsReadAsTheServerWroteItFromItsCursor() = runTest {
        signedIn()
        bodies["/api/v1/receipts"] = """
            {"items":[$workflow,
              {"id":"rcpt_t","subject":{"kind":"something-new","id":"t1","digest":"d"},"initiator":"charles","rules":[],"decisions":[],
               "createdAt":"2026-11-12T09:00:00.000Z","signature":"s"},
              {"id":"rcpt_x","shape":"from a newer server"}],"next":"c3"}
        """.trimIndent()
        val page = RemoteReceiptRepository(calls).page("a1", before = "c2")
        assertEquals("c2", requests.single().url.parameters["before"])
        assertEquals("c3", page.next)
        val (first, second, third) = page.items
        // Every field kept for the signature, the unknown one included.
        assertTrue("newerField" in first.raw)
        val summary = first.summary!!
        assertEquals("rcpt_w", summary.id)
        assertEquals("approved", summary.result)
        assertTrue(summary.selfApproved)
        assertEquals("ada", summary.by)
        assertEquals("workflow" to "release@p1", summary.subjectKind to summary.subjectId)
        assertEquals(1_794_564_000_000L, summary.at)
        assertEquals(
            listOf(ReceiptCheckNode("Build", "passed", RunOrigin.CI), ReceiptCheckNode("Lint", "failed", null), ReceiptCheckNode("Fmt", "passed", RunOrigin.UNKNOWN)),
            summary.checkNodes,
        )
        // Nobody decided: the initiator, at the receipt's time; a kind this version does not know as the server wrote it.
        val task = second.summary!!
        assertNull(task.result)
        assertFalse(task.selfApproved)
        assertEquals("charles", task.by)
        assertEquals("something-new", task.subjectKind)
        // A receipt this version cannot read is still listed, without a summary.
        assertNull(third.summary)
        assertEquals(JsonPrimitive("rcpt_x"), third.raw["id"])
    }

    @Test
    fun theServedKeysAreReadWithTheirRotation() = runTest {
        signedIn()
        bodies["/api/v1/receipts/keys"] = """
            {"keys":[{"kid":"k2","alg":"Ed25519","publicKeyPem":"PEM2","rotation":{"kid":"k1","signature":"c2ln"}},
                     {"kid":"k1","alg":"Ed25519","publicKeyPem":"PEM1"}]}
        """.trimIndent()
        assertEquals(
            listOf(ServedReceiptKey("k2", "Ed25519", "PEM2", KeyHandover("k1", "c2ln")), ServedReceiptKey("k1", "Ed25519", "PEM1")),
            RemoteReceiptRepository(calls).keys("a1"),
        )
    }

    @Test
    fun aServerWithoutReceiptsFailsWithoutItsMessage() = runTest {
        signedIn()
        assertEquals(404, assertFailsWith<AppException.Server> { RemoteReceiptRepository(calls).page("a1") }.statusCode)
    }

    @Test
    fun thePinsAreKeptInTheSecureStoreByServerOrigin() = runTest {
        val store = MemoryCredentialStore()
        val pins = SecureReceiptKeyPinRepository(store)
        assertEquals(emptyMap(), pins.read("https://a.example"), "before a first view")
        pins.write("https://a.example", mapOf("k1" to "ab".repeat(32)))
        assertEquals(mapOf("k1" to "ab".repeat(32)), pins.read("https://a.example"))
        assertEquals(emptyMap(), pins.read("https://b.example"), "each server has its own")
        store.write(SecureReceiptKeyPinRepository.entry("https://b.example"), "[not an object")
        assertNull(pins.read("https://b.example"), "unreadable: fail closed")
        store.write(SecureReceiptKeyPinRepository.entry("https://b.example"), """{"k1":42}""")
        assertNull(pins.read("https://b.example"))
    }
}
