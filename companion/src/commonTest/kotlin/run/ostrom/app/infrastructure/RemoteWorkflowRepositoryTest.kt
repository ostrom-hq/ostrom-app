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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteWorkflowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
}
