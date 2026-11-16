// SPDX-License-Identifier: Apache-2.0
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
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalAsker
import run.ostrom.app.domain.models.ApprovalCommit
import run.ostrom.app.domain.models.ApprovalGate
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteApprovalRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteApprovalRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NotFound) }
    private val accounts = accounts().also { it.save(account()) }
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                answer(request)
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val clients = OstromClientProvider(engine, credentials, accounts, platform)
    private val repository = RemoteApprovalRepository(AccountCalls(clients, accounts))

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun body(request: HttpRequestData) = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private val view = """
        {"approval":{"id":"appr_0001","title":"Push to main","requestedFrom":"ada","channel":"app","status":"pending",
         "shownDigest":"sha256:shown","repo":"acme/api","sha":"c0ffee0","commits":["c0ffee0"],
         "evidence":[{"label":"Tests","value":"412 passed"}],"askedBy":["grace"]},
         "who":{"dev":"grace"},"what":{"action":"push","command":"git push origin main"},"askedVia":[],
         "gate":{"workflowId":"w1","workflowName":"Release","nodeId":"n1","label":"Push to main","approvers":["ada"],"status":"accepted","enforce":true,"reason":"r"},
         "you":{"login":"ada","self":false,"canApprove":true}}
    """.trimIndent()

    @Test
    fun aRequestIsReadAsTheAppShowsIt() = runTest {
        credentials.write("a1", "osm_t")
        answer = json(view)
        val approval = repository.view("a1", "appr_0001")
        assertEquals("/api/v1/approvals/appr_0001/view", requests.single().url.encodedPath)
        assertEquals("Bearer osm_t", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(ApprovalStatus.PENDING, approval.status)
        assertEquals(listOf(ApprovalCommit("c0ffee0")), approval.commits)
        assertEquals(emptyList(), approval.checks)
        assertNull(approval.action)
        assertNull(approval.diffStats)
        // No risk is served: every answer asks the biometrics.
        assertTrue(approval.needsBiometrics)
        assertEquals(ApprovalAsker("grace"), approval.who)
        assertEquals("git push origin main", approval.what?.command)
        assertEquals(ApprovalGate("Release", "Push to main"), approval.gate)
        assertNull(approval.expiresAt)
        assertTrue(approval.viewer.canApprove)
        assertFalse(approval.diffTruncated)
    }

    @Test
    fun commitsAreTheirShasAndAnUnknownStatusIsUnknown() = runTest {
        answer = json(
            """{"pending":[],"recent":[{"approval":{"id":"a","title":"t","requestedFrom":"ada","channel":"app","status":"later",
            "shownDigest":"d","commits":["abc1234"],"decidedAt":"not a date"},
            "who":{},"what":{"action":"push"},"askedVia":[],
            "you":{"login":"ada","self":true,"canApprove":false,"reasonType":"self_approval_refused"}}],
            "operator":"ada","you":{"login":"ada","via":"app"}}""",
        )
        val queue = repository.queue("a1")
        assertEquals("/api/v1/approvals/queue", requests.single().url.encodedPath)
        val recent = queue.recent.single()
        assertEquals(ApprovalStatus.UNKNOWN, recent.status)
        assertEquals(listOf(ApprovalCommit("abc1234")), recent.commits)
        assertNull(recent.decidedAt)
        assertFalse(recent.diffTruncated)
        assertTrue(recent.viewer.self)
        assertEquals("self_approval_refused", recent.viewer.reasonType)
        assertNull(recent.action)
    }

    @Test
    fun anAnswerCarriesTheDigestAndTheNote() = runTest {
        answer = json(view.replace("\"status\":\"pending\"", "\"status\":\"rejected\""))
        val after = repository.answer("a1", "appr_0001", ApprovalAnswer.REJECT, "sha256:shown", "Wait for the migration")
        assertEquals(ApprovalStatus.REJECTED, after.status)
        val request = requests.single()
        assertEquals("/api/v1/approvals/appr_0001/answer", request.url.encodedPath)
        val sent = body(request)
        assertTrue("\"answer\":\"reject\"" in sent, sent)
        assertTrue("\"shownDigest\":\"sha256:shown\"" in sent, sent)
        assertTrue("\"note\":\"Wait for the migration\"" in sent, sent)
    }

    @Test
    fun theUnlockIsBoundToItsRequest() = runTest {
        answer = json("""{"authenticatedAt":"2026-11-13T10:00:00Z"}""")
        repository.unlock("a1", "key-1", "appr_0001")
        val request = requests.single()
        assertEquals("/api/v1/auth/mobile/unlock", request.url.encodedPath)
        val sent = body(request)
        assertTrue("\"unlockKey\":\"key-1\"" in sent && "\"requestId\":\"appr_0001\"" in sent, "The key and the request id are sent")
    }

    @Test
    fun refusalsAreTranslatedWithoutTheServersText() = runTest {
        answer = json("""{"error":{"type":"step_up_required","message":"m","requestBound":true}}""", HttpStatusCode.Forbidden)
        assertTrue(assertFailsWith<AppException.StepUpRequired> { repository.answer("a1", "x", ApprovalAnswer.APPROVE, "d", null) }.requestBound)
        answer = json("""{"error":{"type":"step_up_required","message":"m"}}""", HttpStatusCode.Forbidden)
        assertFalse(assertFailsWith<AppException.StepUpRequired> { repository.answer("a1", "x", ApprovalAnswer.APPROVE, "d", null) }.requestBound)
        answer = json("""{"error":{"type":"shown_digest_mismatch","message":"m"}}""", HttpStatusCode.Conflict)
        assertEquals("shown_digest_mismatch", assertFailsWith<AppException.Server> { repository.answer("a1", "x", ApprovalAnswer.APPROVE, "d", null) }.code)
        answer = json("""{"error":{"type":"org_required","message":"m"}}""", HttpStatusCode.Conflict)
        assertFailsWith<AppException.OrganizationRequired> { repository.queue("a1") }
        answer = json("""{"error":{"type":"not_found","message":"m"}}""", HttpStatusCode.NotFound)
        assertFailsWith<AppException.NotFound> { repository.view("a1", "gone") }
    }
}
