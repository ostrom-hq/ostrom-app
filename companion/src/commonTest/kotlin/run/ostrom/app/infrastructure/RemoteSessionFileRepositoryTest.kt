// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/RemoteSessionFileRepositoryTest.kt; see NOTICE.
package run.ostrom.app.infrastructure

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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.PickedFile
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteCloudSessionRepository
import run.ostrom.app.infrastructure.repositories.RemoteSessionFileRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The policy, an upload and its refusals, and a message naming its files, over a scripted server. */
class RemoteSessionFileRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<ByteArray>()
    private val answers = ArrayDeque<Triple<HttpStatusCode, String, Map<String, String>>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                bodies += request.body.received()
                val (status, text, headers) = answers.removeFirstOrNull() ?: Triple(HttpStatusCode.ServiceUnavailable, "{}", emptyMap())
                respond(ByteReadChannel(text), status, headersOf(*(mapOf(HttpHeaders.ContentType to "application/json") + headers).map { it.key to listOf(it.value) }.toTypedArray()))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val calls = AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts)
    private val files = RemoteSessionFileRepository(calls)
    private val bytes = ByteArray(100) { it.toByte() }

    private fun picked(name: String) = PickedFile(name, bytes.size.toLong()) { Buffer().apply { write(bytes) } }

    private fun stored(sha256: String = SHA256) =
        """{"id":"f1","sessionId":"s1","name":"a.png","size":${bytes.size},"contentType":"image/png","sha256":"$sha256","createdAt":"t","expiresAt":"u"}"""

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    /** The body as a server reads it, so a streamed upload is written out. */
    private suspend fun OutgoingContent.received(): ByteArray = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes()
        is OutgoingContent.WriteChannelContent -> coroutineScope {
            val channel = ByteChannel()
            launch {
                try {
                    writeTo(channel); channel.close()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    channel.cancel(error)
                }
            }
            try {
                channel.toByteArray()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
        else -> ByteArray(0)
    }

    private fun answer(body: String, status: HttpStatusCode = HttpStatusCode.OK, headers: Map<String, String> = emptyMap()) = answers.addLast(Triple(status, body, headers))
    private fun refusal(code: String) = """{"error":{"type":"$code","message":"No."}}"""

    @Test
    fun thePolicyIsMapped() = runTest {
        signedIn()
        answer("""{"available":false,"reason":"file_storage_not_configured","maxFileBytes":20,"maxMessageBytes":25,"maxFilesPerMessage":5,"types":["image/png"]}""")
        val policy = files.policy("a1")
        assertFalse(policy.available); assertEquals("file_storage_not_configured", policy.reason)
        assertEquals(listOf("image/png"), policy.types); assertEquals(5, policy.maxFiles)
        assertEquals("/api/v1/cloud/files/policy", requests.single().url.encodedPath)
    }

    @Test
    fun aFileIsStreamedUnderItsKeyWithItsProgress() = runTest {
        signedIn()
        answer(stored(), HttpStatusCode.Created)
        val progress = mutableListOf<Long>()
        val uploaded = files.upload("a1", "s1", picked("a.png"), "k1") { sent, _ -> progress += sent }
        assertEquals("f1", uploaded.id); assertEquals(100, uploaded.size); assertEquals("image/png", uploaded.contentType)
        assertEquals("/api/v1/cloud/sessions/s1/files", requests.single().url.encodedPath)
        assertEquals("k1", requests.single().headers["Idempotency-Key"])
        assertContentEquals(bytes, bodies.single())
        assertEquals(listOf(0L, 100L), progress)
    }

    @Test
    fun aRefusalKeepsItsCodeAndTheSessionGoneIsNotFound() = runTest {
        signedIn()
        answer(refusal("file_too_large"), HttpStatusCode.PayloadTooLarge)
        val tooLarge = assertFailsWith<AppException.FileRefused> { files.upload("a1", "s1", picked("a.png"), "k1") { _, _ -> } }
        assertEquals("file_too_large", tooLarge.code); assertEquals(413, tooLarge.statusCode)
        assertEquals("a.png", requests.single().url.parameters["name"])

        answer(refusal("upload_quota"), HttpStatusCode.TooManyRequests, mapOf(HttpHeaders.RetryAfter to "3600"))
        val quota = assertFailsWith<AppException.FileRefused> { files.upload("a1", "s1", picked("a.png"), "k2") { _, _ -> } }
        assertEquals("upload_quota", quota.code); assertEquals(3600, quota.retryAfterSeconds)

        answer(refusal("session_not_found"), HttpStatusCode.NotFound)
        assertFailsWith<AppException.NotFound> { files.upload("a1", "s1", picked("a.png"), "k3") { _, _ -> } }

        answer(refusal("org_required"), HttpStatusCode.Conflict)
        assertFailsWith<AppException.OrganizationRequired> { files.upload("a1", "s1", picked("a.png"), "k4") { _, _ -> } }
    }

    @Test
    fun otherStoredBytesOrAnUnreadableFileAreRefusals() = runTest {
        signedIn()
        answer(stored(sha256 = "0".repeat(64)), HttpStatusCode.Created)
        val integrity = assertFailsWith<AppException.FileRefused> { files.upload("a1", "s1", picked("a.png"), "k1") { _, _ -> } }
        assertEquals("file_integrity_failed", integrity.code); assertEquals(0, integrity.statusCode)

        answer(stored(), HttpStatusCode.Created)
        val unreadable = PickedFile("a.png", 100) { Failing() }
        val failure = assertFailsWith<AppException.FileRefused> { files.upload("a1", "s1", unreadable, "k2") { _, _ -> } }
        assertEquals("file_unreadable", failure.code)
    }

    @Test
    fun aMessageNamesItsFiles() = runTest {
        signedIn()
        answer("""{"id":"in1","kind":"message","status":"queued","authorId":"m1","createdAt":"t"}""", HttpStatusCode.Accepted)
        RemoteCloudSessionRepository(calls).send("a1", "s1", "", "k1", listOf("f1", "f2"))
        assertTrue(""""fileIds":["f1","f2"]""" in bodies.single().decodeToString(), bodies.single().decodeToString())
    }

    @Test
    fun typesAreGuessedByExtension() {
        assertEquals("image/jpeg", files.typeFor("IMG.JPG"))
        assertEquals("text/markdown", files.typeFor("notes.md"))
        assertNull(files.typeFor("report.docx"))
    }

    private companion object {
        /** The SHA-256 of [bytes] (0 to 99). */
        const val SHA256 = "bce0aff19cf5aa6a7469a30d61d04e4376e4bbf6381052ee9e7f33925c954d52"
    }

    /** A source whose first read fails. */
    private class Failing : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw IllegalStateException("unreadable")
        override fun close() = Unit
    }
}
