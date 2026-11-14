// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../infrastructure/RemoteWorkflowRepositoryTest.kt; see NOTICE.
package run.ostrom.app.infrastructure

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DraftAction
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftLimits
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.usecases.workflows.WorkflowEdits
import run.ostrom.app.infrastructure.client.OstromClientProvider
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.AccountCalls
import run.ostrom.app.infrastructure.repositories.RemoteWorkflowEditorRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteWorkflowEditorRepositoryTest {
    private val asked = mutableListOf<String>()
    private val bodies = mutableMapOf<String, String>()
    private val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private val accounts = accounts()
    private val credentials = MemoryCredentialStore()

    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                val key = "${request.method.value} ${request.url.encodedPath}"
                asked += key
                (request.body as? OutgoingContent.ByteArrayContent)?.let { bodies[key] = it.bytes().decodeToString() }
                val (status, body) = answers[key] ?: (HttpStatusCode.NotFound to """{"error":{"type":"not_found","message":"m"}}""")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val platform = object : PlatformInfo {
        override val platform = "Test"
        override val deviceModel = "Test"
        override val appVersion = "1.0.0"
    }
    private val repository = RemoteWorkflowEditorRepository(
        AccountCalls(OstromClientProvider(engine, credentials, accounts, platform), accounts), FixedClock(1_763_114_400_000L), { "1234-abcd-5678-efgh-9999" },
    )

    // A gate with its approvers, a wait with a field and a positioned agent: what the editor does not change is kept.
    private val stored = """{"id":"wf1","name":"Release","domain":"engineering","description":"Ship","version":3,"repos":["acme/api"],
        "nodes":[{"id":"write","kind":"agent","label":"Write","position":{"x":1.0,"y":2.0},
                  "run":{"instructions":"Notes for {{inputs.version}}","agent":"claude-code","deliverable":{"type":"json","schema":{"type":"object"}}}},
                 {"id":"ask","kind":"wait","label":"Approve","wait":{"title":"Ship it?","body":"Body","fields":[{"id":"why","type":"text","label":"Why"}],
                  "actions":[{"id":"yes","label":"Yes","requires":["why"]}]}},
                 {"id":"ok","kind":"gate","label":"Gate","approvers":["grace"]},
                 {"id":"call","kind":"workflow","label":"Call","workflowId":"other","arguments":{"v":"1"}},
                 {"id":"c","kind":"check","label":"Check","condition":{"step":"write","path":"outcome","op":"equals","value":"delivered"}}],
        "edges":[{"from":"write","to":"ask","on":"delivered"},{"from":"ask","to":"ok","on":"yes","label":"shown"}],"start":"write","sessionMode":"same",
        "inputs":[{"id":"version","label":"Version","required":true,"maxLength":20}],"limits":{"maxVisits":40},
        "status":"edited","foundAt":"2026-11-01T10:00:00Z","updatedBy":"ada"}"""

    private val pending = """{"confirmationId":"c1","confirmUrl":"https://ostrom.example.org/confirm/c1","expiresAt":"2026-11-14T10:15:00Z"}"""

    private suspend fun signedIn() {
        accounts.save(account())
        credentials.write("a1", "osm_token")
    }

    private fun sent(key: String): JsonObject = Json.parseToJsonElement(bodies.getValue(key)).jsonObject

    @Test
    fun aWorkflowIsReadWholeForTheEditor() = runTest {
        signedIn()
        answers["GET /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored
        val editable = repository.editable("a1", "wf1")
        assertEquals(3, editable.version)
        val draft = editable.draft
        assertEquals("Ship", draft.description)
        assertEquals(DraftLimits(maxVisits = 40), draft.limits)
        val write = draft.node("write")
        assertEquals("Notes for {{inputs.version}}", write?.instruction)
        assertEquals(DraftDeliverable("json", schema = """{"type":"object"}"""), write?.deliverable)
        assertEquals("claude-code", write?.agent)
        val ask = draft.node("ask")
        assertEquals("Ship it?", ask?.question)
        assertEquals("Body", ask?.instruction)
        assertEquals(listOf(DraftAction("yes", "Yes")), ask?.actions)
        assertEquals(NodeKind.GATE, draft.node("ok")?.kind)
        assertEquals(mapOf("v" to "1"), draft.node("call")?.arguments)
        assertTrue(draft.node("c")?.branches == true)
        assertEquals(DraftEdge("ask", "ok", "yes", "shown"), draft.edges.last())
    }

    @Test
    fun aSaveKeepsWhatTheEditorDoesNotChangeAndSendsTheVersionRead() = runTest {
        signedIn()
        answers["GET /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored
        answers["PUT /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored
        val editable = repository.editable("a1", "wf1")
        var draft = WorkflowEdits.renameNode(editable.draft, "write", "draft").copy(name = " Ship it ", description = "  ")
        draft = draft.copy(nodes = draft.nodes.map { if (it.id == "ask") it.copy(question = "Go?", actions = it.actions + DraftAction("no", "No", "danger")) else it })
        draft = WorkflowEdits.setKind(draft, "call", NodeKind.AGENT).let { d -> d.copy(nodes = d.nodes.map { if (it.id == "call") it.copy(instruction = "Do it") else it }) }
        assertEquals(WorkflowWrite.Applied("wf1"), repository.save("a1", editable, draft))
        assertEquals(listOf("GET /api/v1/workflows/wf1", "GET /api/v1/workflows/wf1", "PUT /api/v1/workflows/wf1"), asked)
        val body = sent("PUT /api/v1/workflows/wf1")
        assertEquals("3", body["expectedVersion"]?.jsonPrimitive?.content)
        val workflow = body.getValue("workflow").jsonObject
        assertEquals("Ship it", workflow["name"]?.jsonPrimitive?.content)
        assertNull(workflow["description"])
        assertEquals("edited", workflow["status"]?.jsonPrimitive?.content, "The status stays the console's decision")
        assertEquals("acme/api", workflow["repos"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("draft", workflow["start"]?.jsonPrimitive?.content)
        val nodes = workflow.getValue("nodes").jsonArray.associateBy { it.jsonObject["id"]?.jsonPrimitive?.content }
        val renamed = nodes.getValue("draft").jsonObject
        assertEquals("""{"x":1.0,"y":2.0}""", renamed["position"].toString(), "A renamed node keeps its position")
        assertEquals("""{"type":"object"}""", renamed.getValue("run").jsonObject.getValue("deliverable").jsonObject["schema"].toString())
        val wait = nodes.getValue("ask").jsonObject.getValue("wait").jsonObject
        assertEquals("Go?", wait["title"]?.jsonPrimitive?.content)
        assertEquals("why", wait.getValue("fields").jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content, "The fields the console edits are kept")
        assertEquals("""["why"]""", wait.getValue("actions").jsonArray[0].jsonObject["requires"].toString())
        assertEquals("danger", wait.getValue("actions").jsonArray[1].jsonObject["style"]?.jsonPrimitive?.content)
        assertEquals("""["grace"]""", nodes.getValue("ok").jsonObject["approvers"].toString())
        val retyped = nodes.getValue("call").jsonObject
        assertNull(retyped["workflowId"], "A node whose kind changed starts afresh")
        assertEquals("Do it", retyped.getValue("run").jsonObject["instructions"]?.jsonPrimitive?.content)
        assertTrue(nodes.getValue("c").jsonObject.containsKey("condition"))
        assertEquals("draft", workflow.getValue("edges").jsonArray[0].jsonObject["from"]?.jsonPrimitive?.content)
        assertEquals("""{"maxVisits":40}""", workflow["limits"].toString())
    }

    @Test
    fun aNewWorkflowIsCreatedAcceptedUnderAFreshId() = runTest {
        signedIn()
        answers["POST /api/v1/workflows"] = HttpStatusCode.Created to stored.replace("\"wf1\"", "\"wf_1234abcd5678\"")
        val draft = WorkflowEdits.newDraft().let { d -> d.copy(name = "New", nodes = d.nodes.map { it.copy(title = "Agent", instruction = "Go") }) }
        assertEquals(WorkflowWrite.Applied("wf_1234abcd5678"), repository.create("a1", draft))
        val workflow = sent("POST /api/v1/workflows")
        assertEquals("wf_1234abcd5678", workflow["id"]?.jsonPrimitive?.content)
        assertEquals("accepted", workflow["status"]?.jsonPrimitive?.content)
        assertEquals("2025-11-14T10:00:00Z", workflow["foundAt"]?.jsonPrimitive?.content)
        assertNull(workflow["limits"])
        assertEquals("text", workflow.getValue("nodes").jsonArray.single().jsonObject.getValue("run").jsonObject.getValue("deliverable").jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun aWriteHeldForTheConsoleSaysWhereAndUntilWhen() = runTest {
        signedIn()
        answers["GET /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored
        answers["PUT /api/v1/workflows/wf1"] = HttpStatusCode.Accepted to pending
        answers["POST /api/v1/workflows"] = HttpStatusCode.Accepted to pending
        answers["DELETE /api/v1/workflows/wf1"] = HttpStatusCode.Accepted to pending
        val held = WorkflowWrite.Held("c1", "https://ostrom.example.org/confirm/c1", 1_794_651_300_000L)
        val editable = repository.editable("a1", "wf1")
        assertEquals(held, repository.save("a1", editable, editable.draft))
        assertEquals(held, repository.create("a1", editable.draft))
        assertEquals(held, repository.delete("a1", "wf1"))
        answers["DELETE /api/v1/workflows/wf1"] = HttpStatusCode.NoContent to ""
        assertEquals(WorkflowWrite.Applied(Unit), repository.delete("a1", "wf1"))
    }

    @Test
    fun theRightsAreReadFromTheSettings() = runTest {
        signedIn()
        answers["GET /api/v1/workflows/settings"] = HttpStatusCode.OK to """{"editors":{"mode":"members","memberIds":["m1","m2"]},"canEdit":false}"""
        assertEquals(WorkflowEditRights(false, "members", 2), repository.rights("a1"))
    }

    private suspend fun refusal(method: String, status: Int, type: String, message: String = "m"): WorkflowEditRefusal {
        answers.clear()
        answers["GET /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored
        val key = if (method == "POST") "POST /api/v1/workflows" else "$method /api/v1/workflows/wf1"
        answers[key] = HttpStatusCode.fromValue(status) to """{"error":{"type":"$type","message":"$message"}}"""
        val editable = repository.editable("a1", "wf1")
        val error = assertFailsWith<AppException.WorkflowEditRefused> {
            when (method) {
                "POST" -> repository.create("a1", editable.draft)
                "PUT" -> repository.save("a1", editable, editable.draft)
                else -> repository.delete("a1", "wf1")
            }
        }
        return error.reason
    }

    @Test
    fun eachRefusalIsWordedByItsKey() = runTest {
        signedIn()
        assertEquals(WorkflowEditRefusal.INVALID, refusal("POST", 400, "invalid_workflow", "Invalid workflow: Node x: a label of 1 to 200 characters."))
        assertEquals(WorkflowEditRefusal.EDITORS_ONLY, refusal("POST", 403, "workflow_editors_only"))
        assertEquals(WorkflowEditRefusal.ID_TAKEN, refusal("POST", 409, "conflict"))
        assertEquals(WorkflowEditRefusal.NAME_TAKEN, refusal("POST", 409, "workflow_name_taken"))
        assertEquals(WorkflowEditRefusal.LIMIT, refusal("POST", 409, "workflow_limit"))
        assertEquals(WorkflowEditRefusal.CONFIRMATION_LIMIT, refusal("POST", 429, "confirmation_limit"))
        assertEquals(WorkflowEditRefusal.INVALID, refusal("PUT", 400, "invalid_request"))
        assertEquals(WorkflowEditRefusal.STALE, refusal("PUT", 409, "conflict", "workflow_conflict: someone saved this workflow in between."))
        assertEquals(WorkflowEditRefusal.NAME_TAKEN, refusal("PUT", 409, "workflow_name_taken"))
        assertEquals(WorkflowEditRefusal.IN_USE, refusal("DELETE", 409, "workflow_in_use"))
        assertEquals(WorkflowEditRefusal.CONFIRMATION_LIMIT, refusal("DELETE", 429, "confirmation_limit"))
    }

    @Test
    fun aWorkflowGoneMeanwhileIsNotFound() = runTest {
        signedIn()
        assertFailsWith<AppException.NotFound> { repository.editable("a1", "wf1") }
        assertFailsWith<AppException.NotFound> { repository.delete("a1", "wf1") }
        answers["GET /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored
        val editable = repository.editable("a1", "wf1")
        answers.clear()
        assertFailsWith<AppException.NotFound> { repository.save("a1", editable, editable.draft) }
        answers["GET /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored.replace("\"version\":3", "\"version\":4")
        val stale = assertFailsWith<AppException.WorkflowEditRefused> { repository.save("a1", editable, editable.draft) }
        assertEquals(WorkflowEditRefusal.STALE, stale.reason, "Saved since it was read: refused before anything is sent")
        assertFalse(asked.any { it.startsWith("PUT") })
        answers["PUT /api/v1/workflows/wf1"] = HttpStatusCode.Conflict to """{"error":{"type":"conflict","message":"m"}}"""
        answers["GET /api/v1/workflows/wf1"] = HttpStatusCode.OK to stored
        assertFailsWith<AppException.Server> { repository.save("a1", editable, editable.draft) }
    }
}
