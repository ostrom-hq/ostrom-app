// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/RemoteWorkflowRepositoryTest.kt; see NOTICE.
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
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteWorkflowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteWorkflowRepositoryTest {
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
    private val repository = RemoteWorkflowRepository(AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts))

    private val definition = """{"id":"wf1","name":"Release","domain":"engineering","description":"Ship a version","version":3,"repos":["acme/api"],
        "nodes":[{"id":"t","kind":"trigger","label":"Manual"},{"id":"build","kind":"step","label":"Build","run":{"command":"./gradlew build\nsecond line"}},
                 {"id":"ok","kind":"gate","label":"Approve"},{"id":"x","kind":"teleport","label":"Future"}],
        "edges":[{"from":"t","to":"build"},{"from":"build","to":"ok"}],"start":"t",
        "inputs":[{"id":"version","label":"Version","required":true},{"id":"notes","label":"Notes","default":"none"}],
        "status":"accepted","foundAt":"2026-11-01T10:00:00Z"}"""

    private fun run(id: String, status: String) = """{"id":"$id","workflowId":"wf1","workflowName":"Release","workflowVersion":3,"status":"$status",
        "startedBy":"ada","source":"manual","inputs":{"version":"1.2.0"},"currentNodeId":"ok","currentNodeKind":"gate",
        "visits":[{"seq":1,"nodeId":"build","kind":"step","status":"done","outcome":"passed","nodeTitle":"Build","startedAt":"2026-11-14T09:00:00Z","endedAt":"2026-11-14T09:05:00Z"},
                  {"seq":2,"nodeId":"ok","kind":"gate","status":"active","gateId":"g1","startedAt":"2026-11-14T09:05:00Z"}],
        "startedAt":"2026-11-14T09:00:00Z"}"""

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    @Test
    fun aWorkflowIsReadWithItsNodesAndInputs() = runTest {
        signedIn()
        answers["/api/v1/workflows"] = HttpStatusCode.OK to """{"items":[$definition]}"""
        answers["/api/v1/workflows/wf1"] = HttpStatusCode.OK to definition
        val workflow = repository.workflows("a1").single()
        assertEquals(workflow, repository.workflow("a1", "wf1"))
        assertEquals(listOf(NodeKind.TRIGGER, NodeKind.STEP, NodeKind.GATE, NodeKind.UNKNOWN), workflow.nodes.map { it.kind })
        assertEquals("./gradlew build", workflow.nodes[1].detail, "The first line of what a node does")
        assertEquals(listOf("version" to true, "notes" to false), workflow.inputs.map { it.id to it.required })
        assertEquals("none", workflow.inputs[1].default)
        assertEquals(4096, workflow.inputs[1].maxLength, "No maximum named: the server's default")
        assertEquals(listOf("acme/api"), workflow.repos)
        assertEquals("accepted", workflow.status)
        assertEquals(1_793_527_200_000L, workflow.updatedAt)
        assertFailsWith<AppException.NotFound> { repository.workflow("a1", "gone") }
    }

    @Test
    fun theRunsAreTheMembersOrAWorkflowsAndARunHasItsVisits() = runTest {
        signedIn()
        answers["/api/v1/runs"] = HttpStatusCode.OK to """{"items":[${run("r1", "waiting")},${run("r2", "paused")}],"next":"c2"}"""
        answers["/api/v1/workflows/wf1/runs"] = HttpStatusCode.OK to """{"items":[${run("r1", "succeeded")}]}"""
        answers["/api/v1/runs/r1"] = HttpStatusCode.OK to run("r1", "waiting")
        val mine = repository.runs("a1", null, "waiting", "c1")
        assertEquals("c2", mine.next)
        assertEquals(listOf(RunStatus.WAITING, RunStatus.UNKNOWN), mine.runs.map { it.status })
        assertEquals("waiting", requests[0].url.parameters["status"]); assertEquals("c1", requests[0].url.parameters["before"])
        val ofWorkflow = repository.runs("a1", "wf1", null, null)
        assertNull(ofWorkflow.next)
        assertEquals(RunStatus.SUCCEEDED, ofWorkflow.runs.single().status)
        val run = repository.run("a1", "r1")
        assertEquals(listOf("Build", "ok"), run.visits.map { it.title }, "A visit without its title shows its node's id")
        assertEquals("g1", run.visits[1].gateId)
        assertEquals(NodeKind.GATE, run.currentNodeKind)
        assertEquals("ok", run.currentTitle)
        assertEquals(mapOf("version" to "1.2.0"), run.inputs)
        assertFailsWith<AppException.NotFound> { repository.runs("a1", "gone", null, null) }
    }

    private fun error(type: String, message: String) = """{"error":{"type":"$type","message":"$message"}}"""

    @Test
    fun aRunStartsWithItsInputsAndARunIsCancelled() = runTest {
        signedIn()
        answers["/api/v1/workflows/wf1/runs"] = HttpStatusCode.Created to run("r9", "queued")
        answers["/api/v1/runs/r9/cancel"] = HttpStatusCode.OK to run("r9", "cancelled")
        val started = repository.start("a1", "wf1", mapOf("version" to "1.4.0"), "press-1")
        assertEquals("r9", started.id)
        assertEquals(RunStatus.QUEUED, started.status)
        assertEquals("POST", requests.last().method.value)
        val body = (requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(""""inputs":{"version":"1.4.0"}""" in body, body)
        assertEquals("press-1", requests.last().headers["Idempotency-Key"], "A repeated press carries the same key")
        assertEquals(RunStatus.CANCELLED, repository.cancel("a1", "r9").status)
        assertEquals("POST /api/v1/runs/r9/cancel", requests.last().let { "${it.method.value} ${it.url.encodedPath}" })
    }

    @Test
    fun eachRefusalIsKnownByItsKeyNeverByTheServersText() = runTest {
        signedIn()
        suspend fun refusalOfStart(status: HttpStatusCode, body: String): WorkflowRefusal {
            answers["/api/v1/workflows/wf1/runs"] = status to body
            return assertFailsWith<AppException.WorkflowRefused> { repository.start("a1", "wf1", emptyMap(), "press-2") }.reason
        }
        suspend fun refusalOfCancel(status: HttpStatusCode, body: String): WorkflowRefusal {
            answers["/api/v1/runs/r1/cancel"] = status to body
            return assertFailsWith<AppException.WorkflowRefused> { repository.cancel("a1", "r1") }.reason
        }
        assertEquals(WorkflowRefusal.FORBIDDEN, refusalOfStart(HttpStatusCode.Forbidden, error("forbidden", "Only an owner or an admin starts a run.")))
        assertEquals(WorkflowRefusal.INVALID_INPUTS, refusalOfStart(HttpStatusCode.BadRequest, error("invalid_request", "Fill in version.")))
        assertEquals(WorkflowRefusal.RUN_LIMIT, refusalOfStart(HttpStatusCode.Conflict, error("run_limit", "Twenty runs are unfinished.")))
        assertEquals(WorkflowRefusal.NOT_RUNNABLE, refusalOfStart(HttpStatusCode.Conflict, error("conflict", "workflow_not_accepted: accept the workflow before running it.")))
        assertEquals(WorkflowRefusal.FORBIDDEN, refusalOfCancel(HttpStatusCode.Forbidden, error("forbidden", "No.")))
        assertEquals(WorkflowRefusal.RUN_ENDED, refusalOfCancel(HttpStatusCode.Conflict, error("conflict", "run_ended: the run already ended.")))
        assertEquals(WorkflowRefusal.RUN_BUSY, refusalOfCancel(HttpStatusCode.Conflict, error("conflict", "run_busy: the run changed meanwhile, try again.")))

        answers["/api/v1/workflows/wf1/runs"] = HttpStatusCode.Conflict to error("conflict", "something_else: new")
        val other = assertFailsWith<AppException.Server> { repository.start("a1", "wf1", emptyMap(), "press-2") }
        assertEquals(409, other.statusCode)
        answers.remove("/api/v1/workflows/wf1/runs")
        assertFailsWith<AppException.NotFound> { repository.start("a1", "wf1", emptyMap(), "press-2") }
        answers.remove("/api/v1/runs/r1/cancel")
        assertFailsWith<AppException.NotFound> { repository.cancel("a1", "r1") }
        answers["/api/v1/runs/r1/cancel"] = HttpStatusCode.Unauthorized to error("unauthorized", "m")
        assertFailsWith<AppException.Unauthorized> { repository.cancel("a1", "r1") }
    }
}
