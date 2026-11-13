// SPDX-License-Identifier: AGPL-3.0-only
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
import run.ostrom.app.domain.models.ActionKind
import run.ostrom.app.domain.models.ActionRisk
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalAsker
import run.ostrom.app.domain.models.ApprovalCheck
import run.ostrom.app.domain.models.ApprovalCommit
import run.ostrom.app.domain.models.ApprovalGate
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.CheckState
import run.ostrom.app.domain.models.DiffStats
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
         "commitDetails":[{"sha":"c0ffee0","title":"Fix the login","author":"grace"}],
         "checks":[{"name":"ci","state":"success"},{"name":"lint","state":"brand-new"}],
         "evidence":[{"label":"Tests","value":"412 passed"}],"askedBy":["grace"],
         "expiresAt":"2026-12-13T10:00:00Z","action":{"kind":"push","repo":"acme/api","ref":"main","headSha":"c0ffee0","risk":"high"},
         "diffStats":{"files":3,"additions":40,"deletions":2}},
         "who":{"dev":"grace","agent":"claude-code","model":"opus"},"what":{"command":"git push origin main"},
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
        assertEquals(listOf(ApprovalCommit("c0ffee0", "Fix the login", "grace")), approval.commits)
        assertEquals(listOf(ApprovalCheck("ci", CheckState.SUCCESS), ApprovalCheck("lint", CheckState.UNKNOWN)), approval.checks)
        assertEquals(ActionKind.PUSH, approval.action?.kind)
        assertEquals(ActionRisk.HIGH, approval.action?.risk)
        assertEquals(DiffStats(3, 40, 2), approval.diffStats)
        assertEquals(ApprovalAsker("grace", "claude-code", "opus"), approval.who)
        assertEquals("git push origin main", approval.what?.command)
        assertEquals(ApprovalGate("Release", "Push to main"), approval.gate)
        assertEquals(1_797_156_000_000L, approval.expiresAt)
        assertTrue(approval.viewer.canApprove)
        assertFalse(approval.diffTruncated)
    }

    @Test
    fun commitsWithoutDetailsAreTheirShasAndAnUnknownStatusIsUnknown() = runTest {
        answer = json(
            """{"pending":[],"recent":[{"approval":{"id":"a","title":"t","requestedFrom":"ada","channel":"app","status":"later",
            "shownDigest":"d","commits":["abc1234"],"decidedAt":"not a date","diffTruncated":true},
            "you":{"login":"ada","self":true,"canApprove":false,"reasonType":"self_approval_refused"}}],
            "operator":"ada","you":{"login":"ada","via":"app"}}""",
        )
        val queue = repository.queue("a1")
        assertEquals("/api/v1/approvals/queue", requests.single().url.encodedPath)
        val recent = queue.recent.single()
        assertEquals(ApprovalStatus.UNKNOWN, recent.status)
        assertEquals(listOf(ApprovalCommit("abc1234")), recent.commits)
        assertNull(recent.decidedAt)
        assertTrue(recent.diffTruncated)
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
