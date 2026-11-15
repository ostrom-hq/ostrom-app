// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/RemoteTransferRepositoryTest.kt; see NOTICE.
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
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteCloudSessionRepository
import run.ostrom.app.infrastructure.repositories.RemoteTransferRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transfers over a scripted server: what is sent, the mapping, and refusals kept with their error keys. */
class RemoteTransferRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers.removeFirstOrNull() ?: (HttpStatusCode.ServiceUnavailable to "{}")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val calls = AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts)
    private val transfers = RemoteTransferRepository(calls)

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = answers.addLast(status to body)

    private fun transferJson(id: String, state: String, extra: String = "") =
        """{"id":"$id","sessionId":"s1","fromMemberId":"m1","toMemberId":"m2","state":"$state","initiatedBy":"m1","resume":true,
            "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z","expiresAt":"2026-11-21T09:00:00Z"$extra}"""

    @Test fun aTransferSendsItsKeyAndResumeAndMapsTheAnswer() = runTest {
        signedIn()
        json(transferJson("t1", "accepted"), HttpStatusCode.Accepted)
        val transfer = transfers.transfer("a1", "s1", TransferRequest("m2", resume = true), "key-1")
        assertEquals(TransferState.ACCEPTED, transfer.state)
        assertTrue(transfer.resume)
        assertEquals(1_795_251_600_000L, transfer.expiresAt)
        val request = requests.single()
        assertEquals("/api/v1/cloud/sessions/s1/transfer", request.url.encodedPath)
        assertEquals("key-1", request.headers["Idempotency-Key"])
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(body.contains("\"toMemberId\":\"m2\"") && body.contains("\"resume\":true"), body)
    }

    @Test fun aRefusalKeepsItsErrorKeyAndAGoneTransferStaysNotFound() = runTest {
        signedIn()
        json("""{"error":{"type":"recipient_ineligible","message":"The recipient cannot own this session: model_not_allowed."}}""", HttpStatusCode.UnprocessableEntity)
        assertEquals("recipient_ineligible", assertFailsWith<AppException.TransferRefused> { transfers.transfer("a1", "s1", TransferRequest("m2"), "k") }.code)
        json("""{"error":{"type":"transfer_pending","message":"x"}}""", HttpStatusCode.Conflict)
        assertEquals("transfer_pending", assertFailsWith<AppException.TransferRefused> { transfers.cancel("a1", "t1") }.code)
        // A session or transfer that is gone stays "not found", never a refusal.
        json("""{"error":{"type":"not_found","message":"x"}}""", HttpStatusCode.NotFound)
        assertFailsWith<AppException.NotFound> { transfers.accept("a1", "t1", null) }
    }

    @Test fun theListAsksForOneDirectionAndKeepsAFailureCode() = runTest {
        signedIn()
        json("""{"items":[${transferJson("t2", "failed", ",\"failure\":{\"code\":\"recipient_no_quota\",\"message\":\"x\"}")}, ${transferJson("t1", "later_state")}],"next":"t1"}""")
        val page = transfers.transfers("a1", TransferDirection.OUTGOING, before = "t3")
        assertEquals(listOf(TransferState.FAILED, TransferState.UNKNOWN), page.transfers.map { it.state })
        assertEquals("recipient_no_quota", page.transfers[0].problemCode)
        assertNull(page.transfers[1].problemCode)
        assertEquals("t1", page.nextBefore)
        val url = requests.single().url
        assertEquals("outgoing", url.parameters["direction"])
        assertEquals("t3", url.parameters["before"])
        assertEquals("50", url.parameters["limit"])
    }

    @Test fun aDecisionIsPostedToItsTransferAndAcceptTakesNoResumeByDefault() = runTest {
        signedIn()
        json(transferJson("t1", "accepted"))
        assertEquals(TransferState.ACCEPTED, transfers.accept("a1", "t1", null).state)
        json(transferJson("t1", "declined"))
        assertEquals(TransferState.DECLINED, transfers.decline("a1", "t1").state)
        assertEquals(listOf("/api/v1/cloud/transfers/t1/accept", "/api/v1/cloud/transfers/t1/decline"), requests.map { it.url.encodedPath })
    }

    @Test fun recipientsComeByLoginAndARefusalKeepsItsKey() = runTest {
        signedIn()
        json("""{"recipients":[{"id":"m1","login":"ada","administrator":true},{"id":"m2","login":"bob","administrator":false}]}""")
        val recipients = transfers.recipients("a1")
        assertEquals(listOf("ada" to true, "bob" to false), recipients.map { it.username to it.administrator })
        assertEquals("/api/v1/cloud/transfer-recipients", requests.single().url.encodedPath)
        json("""{"error":{"type":"transfer_disabled","message":"x"}}""", HttpStatusCode.Forbidden)
        assertEquals("transfer_disabled", assertFailsWith<AppException.TransferRefused> { transfers.recipients("a1") }.code)
    }

    @Test fun aCloudSessionCarriesItsOwnerItsTransferAndItsKind() = runTest {
        signedIn()
        val session = """{"id":"c1","name":"Mill","owner":"m1","agent":"claude","status":"transferring","transferId":"t1","kind":"remote",
            "createdAt":"2026-11-14T09:00:00Z"}"""
        json(session)
        val read = RemoteCloudSessionRepository(calls).session("a1", "c1")
        assertEquals(run.ostrom.app.domain.models.CloudSessionStatus.TRANSFERRING, read.status)
        assertEquals("m1", read.ownerId)
        assertEquals("t1", read.transferId)
        assertTrue(read.remote)
    }
}
