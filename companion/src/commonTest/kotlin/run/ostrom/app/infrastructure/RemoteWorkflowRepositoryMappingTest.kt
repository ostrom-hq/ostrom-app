// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.infrastructure.repositories.RemoteWorkflowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The workflow definitions and runs as the app reads them: what a node says it does, every kind and status, every refusal. */
class RemoteWorkflowRepositoryMappingTest {
    private val server = ScriptedServer()
    private val repository = RemoteWorkflowRepository(server.calls)

    private fun workflow(nodes: String, extra: String = "") =
        """{"id":"wf1","name":"Release","domain":"engineering","nodes":[$nodes],"edges":[],"status":"accepted","foundAt":"2026-11-01T10:00:00Z"$extra}"""

    private fun node(id: String, kind: String, extra: String = "") = """{"id":"$id","kind":"$kind","label":"L-$id"$extra}"""

    @Test fun aNodeTellsWhatItDoesFromTheFirstFieldThatHasTextOnItsFirstLine() = runTest {
        server.signedIn()
        server.json(
            """{"items":[${workflow(
                listOf(
                    node("detail", "step", ""","detail":"Open the PR\nthen wait""""),
                    node("blankDetail", "step", ""","detail":"  ","run":{"command":"make test"}"""),
                    node("instructions", "agent", ""","run":{"instructions":"Fix the bug"}"""),
                    node("message", "notify", ""","run":{"message":"Done!"}"""),
                    node("wait", "wait", ""","wait":{"title":"Which one?"}"""),
                    node("calls", "workflow", ""","workflowId":"child""""),
                    node("stray", "step", ""","workflowId":"other""""),
                    node("nothing", "check"),
                ).joinToString(","),
            )}]}""",
        )
        val nodes = repository.workflows("a1").single().nodes.associateBy { it.id }
        assertEquals("Open the PR", nodes.getValue("detail").detail)
        assertEquals("make test", nodes.getValue("blankDetail").detail, "a blank detail is skipped")
        assertEquals("Fix the bug", nodes.getValue("instructions").detail)
        assertEquals("Done!", nodes.getValue("message").detail)
        assertEquals("Which one?", nodes.getValue("wait").detail)
        assertEquals("→ child", nodes.getValue("calls").detail)
        assertEquals("child", nodes.getValue("calls").calls)
        assertEquals("→ other", nodes.getValue("stray").detail)
        assertNull(nodes.getValue("stray").calls, "only a workflow node calls another workflow")
        assertNull(nodes.getValue("nothing").detail)
    }

    @Test fun everyNodeKindIsMappedAndAnUnknownOneIsKept() = runTest {
        server.signedIn()
        val kinds = listOf("trigger", "step", "check", "gate", "notify", "agent", "wait", "workflow", "from-the-future")
        server.json("""{"items":[${workflow(kinds.joinToString(",") { node(it, it) })}]}""")
        assertEquals(
            listOf(NodeKind.TRIGGER, NodeKind.STEP, NodeKind.CHECK, NodeKind.GATE, NodeKind.NOTIFY, NodeKind.AGENT, NodeKind.WAIT, NodeKind.WORKFLOW, NodeKind.UNKNOWN),
            repository.workflows("a1").single().nodes.map { it.kind },
        )
    }

    @Test fun aWorkflowWithoutReposOrUpdateTimeFallsBackToWhenItWasFound() = runTest {
        server.signedIn()
        server.json("""{"items":[${workflow(node("t", "trigger"))}]}""")
        val found = repository.workflows("a1").single()
        assertEquals(emptyList(), found.repos)
        assertEquals(1_793_527_200_000L, found.updatedAt)
        server.json("""{"items":[${workflow(node("t", "trigger"), ""","updatedAt":"2026-11-02T10:00:00Z","repos":["a/b"]""")}]}""")
        val updated = repository.workflows("a1").single()
        assertEquals(1_793_613_600_000L, updated.updatedAt)
        assertEquals(listOf("a/b"), updated.repos)
    }

    private fun run(status: String, extra: String = "") = """{"id":"r1","workflowId":"wf1","workflowName":"Release","workflowVersion":3,"status":"$status",
        "startedBy":"ada","source":"manual","startedAt":"2026-11-14T09:00:00Z"$extra}"""

    @Test fun everyRunStatusIsMappedAndANewOneReadsAsUnknown() = runTest {
        server.signedIn()
        val statuses = listOf("queued", "running", "waiting", "succeeded", "failed", "cancelled", "unknown", "paused-by-future")
        statuses.forEach { server.json(run(it)) }
        val read = statuses.map { repository.run("a1", "r1").status }
        assertEquals(RunStatus.entries.first { it.wire == "queued" }, read[0])
        assertEquals(RunStatus.UNKNOWN, read[6], "the unknown status is never matched by name")
        assertEquals(RunStatus.UNKNOWN, read[7])
    }

    @Test fun aRunKeepsItsFailureOutputReceiptParentAndEveryVisitsLinks() = runTest {
        server.signedIn()
        server.json(
            run(
                "failed",
                ""","failure":"Step failed","failureCode":"session_failed","output":"partial","receiptId":"rc1","parentRunId":"r0","sessionId":"s1","repository":"acme/api",
                "endedAt":"2026-11-14T10:00:00Z","currentNodeId":"n1","currentNodeKind":"agent",
                "visits":[{"seq":1,"nodeId":"n1","kind":"agent","status":"failed","sessionId":"s1","waitId":"w1","childRunId":"r2","output":"o","startedAt":"2026-11-14T09:00:00Z"}]""",
            ),
        )
        val read = repository.run("a1", "r1")
        assertEquals("Step failed", read.failure)
        assertEquals("session_failed", read.failureCode)
        assertEquals("rc1", read.receiptId)
        assertEquals("r0", read.parentRunId)
        assertEquals("acme/api", read.repository)
        assertEquals(1_794_650_400_000L, read.endedAt)
        assertEquals(NodeKind.AGENT, read.currentNodeKind)
        val visit = read.visits.single()
        assertEquals(listOf("s1", "w1", "r2", "o"), listOf(visit.sessionId, visit.waitId, visit.childRunId, visit.output))
        assertNull(visit.endedAt)
    }

    @Test fun aRunWithoutACurrentNodeOrVisitsHasNone() = runTest {
        server.signedIn()
        server.json(run("queued"))
        val read = repository.run("a1", "r1")
        assertNull(read.currentNodeKind)
        assertEquals(emptyList(), read.visits)
        assertNull(read.endedAt)
    }

    private suspend fun refusal(status: HttpStatusCode, type: String, message: String): Exception {
        server.json("""{"error":{"type":"$type","message":"$message"}}""", status)
        return assertFailsWith<Exception> { repository.start("a1", "wf1", emptyMap(), "k") }
    }

    @Test fun aRefusalWithTheRightStatusButAnotherKeyIsNotThatRefusal() = runTest {
        server.signedIn()
        assertEquals(403, (refusal(HttpStatusCode.Forbidden, "other", "m") as AppException.Server).statusCode)
        assertEquals(400, (refusal(HttpStatusCode.BadRequest, "other", "m") as AppException.Server).statusCode)
        assertEquals(409, (refusal(HttpStatusCode.Conflict, "other", "plain words") as AppException.Server).statusCode)
        assertEquals(500, (refusal(HttpStatusCode.InternalServerError, "forbidden", "m") as AppException.Server).statusCode)
        assertEquals(400, (refusal(HttpStatusCode.BadRequest, "conflict", "workflow_not_accepted: no") as AppException.Server).statusCode)
    }

    @Test fun aRunEndedIsRecognizedByItsTypeAloneToo() = runTest {
        server.signedIn()
        val failure = refusal(HttpStatusCode.Conflict, "run_ended", "The run is over.")
        assertEquals(WorkflowRefusal.RUN_ENDED, (failure as AppException.WorkflowRefused).reason)
    }
}
