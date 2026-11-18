// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import run.ostrom.app.FixedClock
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DraftAction
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftLimits
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.infrastructure.repositories.RemoteWorkflowEditorRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the editor reads and sends for every kind of node and every optional field, and how each refusal is told. */
class RemoteWorkflowEditorMappingTest {
    private val server = ScriptedServer()
    private val repository = RemoteWorkflowEditorRepository(server.calls, FixedClock(1_763_114_400_000L)) { "ab-12 cd!34" }

    private val minimal = """{"id":"wf_new","name":"N","domain":"d","nodes":[],"edges":[],"status":"accepted","foundAt":"2026-11-01T10:00:00Z"}"""

    private fun node(id: String, kind: NodeKind, extra: DraftNode.() -> DraftNode = { this }) = DraftNode(id, kind, " $id ").extra()

    private suspend fun sentByCreate(draft: WorkflowDraft): JsonObject {
        server.signedIn()
        server.json(minimal, HttpStatusCode.Created)
        assertEquals(WorkflowWrite.Applied("wf_new"), repository.create("a1", draft))
        return Json.parseToJsonElement(server.bodyOf(server.requests.last())).jsonObject
    }

    private fun JsonObject.nodeOf(id: String) = getValue("nodes").jsonArray.map { it.jsonObject }.first { it["id"]?.jsonPrimitive?.content == id }

    @Test fun aNewWorkflowSendsEveryKindOfNodeAndTrimsWhatIsTyped() = runTest {
        val kinds = listOf(NodeKind.TRIGGER, NodeKind.STEP, NodeKind.CHECK, NodeKind.GATE, NodeKind.NOTIFY, NodeKind.AGENT, NodeKind.WAIT, NodeKind.WORKFLOW)
        val draft = WorkflowDraft(
            name = "  Ship  ", description = "  ", domain = " eng ", start = "TRIGGER", sessionMode = null,
            inputs = listOf(WorkflowInput("v", " Version ", true, "", 10), WorkflowInput("n", "Notes", false, "none", 20)),
            nodes = kinds.map { node(it.name, it) },
            edges = listOf(DraftEdge("TRIGGER", "STEP", on = "next", label = "go")),
            limits = DraftLimits(maxNodes = 9),
        )
        val body = sentByCreate(draft)
        assertEquals("wf_ab12cd34", body["id"]?.jsonPrimitive?.content, "the id is drawn from letters and digits only")
        assertEquals("Ship", body["name"]?.jsonPrimitive?.content)
        assertEquals("eng", body["domain"]?.jsonPrimitive?.content)
        assertNull(body["description"], "a blank description is not sent")
        assertEquals(kinds.map { it.name.lowercase() }, body.getValue("nodes").jsonArray.map { it.jsonObject["kind"]?.jsonPrimitive?.content })
        assertEquals("STEP", body.nodeOf("STEP")["label"]?.jsonPrimitive?.content, "labels are trimmed")
        val inputs = body.getValue("inputs").jsonArray.map { it.jsonObject }
        assertEquals("Version", inputs[0]["label"]?.jsonPrimitive?.content)
        assertNull(inputs[0]["default"], "an empty default is none")
        assertEquals("none", inputs[1]["default"]?.jsonPrimitive?.content)
        assertEquals("""{"maxNodes":9}""", body["limits"].toString())
        val edge = body.getValue("edges").jsonArray.single().jsonObject
        assertEquals(listOf("go", "next"), listOf(edge["label"]?.jsonPrimitive?.content, edge["on"]?.jsonPrimitive?.content))
    }

    @Test fun anAgentSendsItsInstructionsAgentModelAndDeliverableWithBlanksLeftOut() = runTest {
        val body = sentByCreate(
            WorkflowDraft(
                name = "n",
                nodes = listOf(
                    node("full", NodeKind.AGENT) {
                        copy(
                            instruction = "Do it", agent = " claude ", model = " opus ",
                            deliverable = DraftDeliverable("json", " Report ", " eng ", """{"type":"object"}"""),
                        )
                    },
                    node("blank", NodeKind.AGENT) {
                        copy(instruction = " ", agent = " ", model = "", deliverable = DraftDeliverable("text", " ", " ", " "))
                    },
                    node("bare", NodeKind.AGENT) { copy(agent = null, model = null, deliverable = DraftDeliverable("text")) },
                    node("none", NodeKind.AGENT),
                ),
            ),
        )
        val full = body.nodeOf("full").getValue("run").jsonObject
        assertEquals(listOf("Do it", "claude", "opus"), listOf("instructions", "agent", "model").map { full[it]?.jsonPrimitive?.content })
        val deliverable = full.getValue("deliverable").jsonObject
        assertEquals(listOf("json", "Report", "eng"), listOf("type", "label", "domain").map { deliverable[it]?.jsonPrimitive?.content })
        assertEquals("""{"type":"object"}""", deliverable["schema"].toString())
        val blank = body.nodeOf("blank").getValue("run").jsonObject
        assertTrue(listOf("instructions", "agent", "model").none { it in blank }, blank.toString())
        assertEquals(listOf("type"), blank.getValue("deliverable").jsonObject.keys.toList())
        assertEquals(listOf("type"), body.nodeOf("bare").getValue("run").jsonObject.getValue("deliverable").jsonObject.keys.toList())
        assertNull(body.nodeOf("none").getValue("run").jsonObject["deliverable"])
    }

    @Test fun aWaitSendsItsQuestionBodyAndActionsAndAWorkflowNodeItsCallee() = runTest {
        val body = sentByCreate(
            WorkflowDraft(
                name = "n",
                nodes = listOf(
                    node("ask", NodeKind.WAIT) { copy(question = " Ship it? ", instruction = "Body", actions = listOf(DraftAction("yes", " Yes ", "primary"), DraftAction("no", "No"))) },
                    node("askBare", NodeKind.WAIT) { copy(question = "Q") },
                    node("call", NodeKind.WORKFLOW) { copy(workflowId = " other ", arguments = mapOf("v" to "1")) },
                    node("callBare", NodeKind.WORKFLOW) { copy(workflowId = " ") },
                    node("callNone", NodeKind.WORKFLOW),
                ),
            ),
        )
        val ask = body.nodeOf("ask").getValue("wait").jsonObject
        assertEquals("Ship it?", ask["title"]?.jsonPrimitive?.content)
        assertEquals("Body", ask["body"]?.jsonPrimitive?.content)
        assertEquals(listOf("Yes", "No"), ask.getValue("actions").jsonArray.map { it.jsonObject["label"]?.jsonPrimitive?.content })
        assertEquals("primary", ask.getValue("actions").jsonArray[0].jsonObject["style"]?.jsonPrimitive?.content)
        assertNull(body.nodeOf("askBare").getValue("wait").jsonObject["body"], "an empty body is none")
        assertEquals("other", body.nodeOf("call")["workflowId"]?.jsonPrimitive?.content)
        assertEquals("1", body.nodeOf("call").getValue("arguments").jsonObject["v"]?.jsonPrimitive?.content)
        assertNull(body.nodeOf("callBare")["workflowId"])
        assertNull(body.nodeOf("callNone")["arguments"], "no arguments are not sent as an empty map")
    }

    @Test fun aNodeOfAKindThisVersionDoesNotKnowCannotBeSent() = runTest {
        server.signedIn()
        assertFailsWith<AppException.Unsupported> { repository.create("a1", WorkflowDraft(name = "n", nodes = listOf(node("x", NodeKind.UNKNOWN)))) }
        assertTrue(server.requests.isEmpty(), "nothing is sent")
    }

    private val stored = """{"id":"wf1","name":"R","domain":"eng","version":3,"status":"accepted","foundAt":"2026-11-01T10:00:00Z","edges":[],
        "nodes":[{"id":"ask","kind":"wait","label":"Ask","wait":{"title":"Q","fields":[],"actions":[{"id":"yes","label":"Yes","requires":["why"]}]}},
                 {"id":"agent","kind":"agent","label":"Agent","run":{"instructions":"old","agent":"claude","model":"opus","deliverable":{"type":"json","label":"L","domain":"eng"}}},
                 {"id":"plain","kind":"step","label":"Plain"},
                 {"id":"check","kind":"check","label":"Check","condition":{"step":"agent","path":"outcome","op":"equals","value":"x"}},
                 {"id":"checkBare","kind":"check","label":"Check bare"},
                 {"id":"waitBare","kind":"wait","label":"Wait bare"},
                 {"id":"stray","kind":"step","label":"Stray","condition":{"step":"agent","path":"outcome","op":"equals","value":"x"}}]}"""

    @Test fun aWorkflowIsReadWithEachNodesOptionalPartsOrTheirAbsence() = runTest {
        server.signedIn()
        server.json(stored)
        val draft = repository.editable("a1", "wf1").draft
        assertEquals("", draft.description)
        assertEquals(DraftLimits(), draft.limits)
        val agent = draft.node("agent")!!
        assertEquals(DraftDeliverable("json", "L", "eng", null), agent.deliverable)
        assertEquals(listOf("old", "claude", "opus"), listOf(agent.instruction, agent.agent, agent.model))
        assertEquals(listOf(DraftAction("yes", "Yes", null)), draft.node("ask")!!.actions)
        assertEquals("Q", draft.node("ask")!!.question)
        assertEquals("", draft.node("ask")!!.instruction, "a wait with no body has an empty one")
        val plain = draft.node("plain")!!
        assertEquals(listOf("", "", null, null, null), listOf(plain.instruction, plain.question, plain.agent, plain.model, plain.deliverable))
        assertTrue(draft.node("check")!!.branches)
        assertFalse(draft.node("checkBare")!!.branches)
        assertFalse(draft.node("stray")!!.branches, "only a check node branches")
        assertEquals("", draft.node("waitBare")!!.question)
    }

    @Test fun aSaveKeepsAWaitsRequirementsAndStartsAfreshANodeWhoseOriginIsGoneOrOfAnotherKind() = runTest {
        server.signedIn()
        server.json(stored)
        val editable = repository.editable("a1", "wf1")
        server.json(stored)
        server.json(minimal)
        val draft = editable.draft.copy(
            nodes = editable.draft.nodes.map {
                when (it.id) {
                    "ask" -> it.copy(actions = listOf(DraftAction("yes", "Yes"), DraftAction("brand-new", "New")))
                    "plain" -> it.copy(originId = "vanished")
                    else -> it
                }
            },
        )
        repository.save("a1", editable, draft)
        val sent = Json.parseToJsonElement(server.bodyOf(server.requests.last())).jsonObject.getValue("workflow").jsonObject
        val actions = sent.nodeOf("ask").getValue("wait").jsonObject.getValue("actions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("why"), actions[0].getValue("requires").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(actions[1]["requires"]?.jsonArray.orEmpty().isEmpty(), "a new action requires nothing")
        assertEquals("plain", sent.nodeOf("plain")["id"]?.jsonPrimitive?.content)
        assertEquals("step", sent.nodeOf("plain")["kind"]?.jsonPrimitive?.content)
    }

    @Test fun theRightsCountNoMembersWhenTheListIsAbsent() = runTest {
        server.signedIn()
        server.json("""{"editors":{"mode":"admins"},"canEdit":true}""")
        assertEquals(WorkflowEditRights(true, "admins", 0), repository.rights("a1"))
    }

    private suspend fun create(status: HttpStatusCode, type: String, message: String = "m"): Exception {
        server.signedIn()
        server.json("""{"error":{"type":"$type","message":"$message"}}""", status)
        return assertFailsWith<Exception> { repository.create("a1", WorkflowDraft(name = "n")) }
    }

    @Test fun eachWritingRefusalIsKnownByItsTypeOrByTheKeyOfItsMessage() = runTest {
        assertEquals(WorkflowEditRefusal.EDITORS_ONLY, (create(HttpStatusCode.Forbidden, "x", "workflow_editors_only: no") as AppException.WorkflowEditRefused).reason)
        assertEquals(WorkflowEditRefusal.INVALID, (create(HttpStatusCode.BadRequest, "invalid_workflow") as AppException.WorkflowEditRefused).reason)
        assertEquals(WorkflowEditRefusal.INVALID, (create(HttpStatusCode.BadRequest, "x", "invalid_request: bad") as AppException.WorkflowEditRefused).reason)
        assertEquals(WorkflowEditRefusal.ID_TAKEN, (create(HttpStatusCode.Conflict, "conflict") as AppException.WorkflowEditRefused).reason)
        assertEquals(WorkflowEditRefusal.IN_USE, (create(HttpStatusCode.Conflict, "workflow_in_use") as AppException.WorkflowEditRefused).reason)
    }

    @Test fun anInvalidKeyOnTheWrongStatusAndAConflictOnlyForACreationAreNotRefusalsOfThatKind() = runTest {
        assertEquals(500, (create(HttpStatusCode.InternalServerError, "invalid_workflow") as AppException.Server).statusCode)
        assertEquals(400, (create(HttpStatusCode.BadRequest, "other") as AppException.Server).statusCode)
        assertEquals(500, (create(HttpStatusCode.InternalServerError, "other") as AppException.Server).statusCode)
    }

    @Test fun aSaveOfAWorkflowWithoutAVersionIsRefusedWhenTheStoredOneHasOne() = runTest {
        server.signedIn()
        server.json(stored.replace("\"version\":3,", ""))
        val editable = repository.editable("a1", "wf1")
        assertNull(editable.version)
        server.json(stored)
        assertEquals(WorkflowEditRefusal.STALE, assertFailsWith<AppException.WorkflowEditRefused> { repository.save("a1", editable, editable.draft) }.reason)
    }
}
