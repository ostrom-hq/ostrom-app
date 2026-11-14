// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/WorkflowEditingUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DraftAction
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftLimits
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.ProblemRule
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.repositories.WorkflowEditorRepository
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.usecases.workflows.DeleteWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadEditableWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowEditRightsUseCase
import run.ostrom.app.domain.usecases.workflows.SaveWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.WorkflowAccess
import run.ostrom.app.domain.usecases.workflows.WorkflowChecks
import run.ostrom.app.domain.usecases.workflows.WorkflowEdits
import run.ostrom.app.domain.usecases.workflows.confirmationLink
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A workflow the server plays: an agent that delivers, then a question whose two buttons end it or loop back. */
internal fun playedDraft() = WorkflowDraft(
    name = "Release", domain = "engineering", start = "write",
    inputs = listOf(WorkflowInput("version", "Version", required = true, default = null)),
    nodes = listOf(
        DraftNode("write", NodeKind.AGENT, "Write the notes", instruction = "Notes for {{inputs.version}}", deliverable = DraftDeliverable("text"), originId = "write"),
        DraftNode("ask", NodeKind.WAIT, "Approve", question = "Ship it?", instruction = "{{nodes.write.output}}",
            actions = listOf(DraftAction("yes", "Yes"), DraftAction("again", "Again")), originId = "ask"),
    ),
    edges = listOf(DraftEdge("write", "ask", "delivered"), DraftEdge("ask", null, "yes"), DraftEdge("ask", "write", "again")),
)

private fun rules(draft: WorkflowDraft, others: List<Workflow>? = null, selfId: String? = null) = WorkflowChecks.check(draft, others, selfId).map { it.rule }.toSet()

private fun callee(id: String, name: String = id, inputs: List<WorkflowInput> = emptyList(), calls: String? = null) = Workflow(
    id, name, "d", null, 1, emptyList(), "accepted", null, inputs,
    listOfNotNull(WorkflowNode("a", NodeKind.AGENT, "A", null), calls?.let { WorkflowNode("c", NodeKind.WORKFLOW, "C", null, it) }), null,
)

class WorkflowEditingTest {
    private class NoRuns : WorkflowRepository {
        override suspend fun workflows(accountId: String) = emptyList<Workflow>()
        override suspend fun workflow(accountId: String, workflowId: String): Workflow = throw AppException.NotFound()
        override suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?) = WorkflowRunPage(emptyList(), null)
        override suspend fun run(accountId: String, runId: String): WorkflowRun = throw AppException.NotFound()
        override suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>): WorkflowRun = throw AppException.NotFound()
        override suspend fun cancel(accountId: String, runId: String): WorkflowRun = throw AppException.NotFound()
    }

    private class FakeEditor(var editable: EditableWorkflow = EditableWorkflow("wf1", 3, playedDraft())) : WorkflowEditorRepository {
        val calls = mutableListOf<String>()
        var held = false
        override suspend fun rights(accountId: String): WorkflowEditRights { calls += "rights"; return WorkflowEditRights(true, "admins", 0) }
        override suspend fun editable(accountId: String, workflowId: String): EditableWorkflow { calls += "get $workflowId"; return editable }
        override suspend fun create(accountId: String, draft: WorkflowDraft): WorkflowWrite<String> { calls += "create ${draft.name}"; return write("wf9") }
        override suspend fun save(accountId: String, workflow: EditableWorkflow, draft: WorkflowDraft): WorkflowWrite<String> {
            calls += "save ${workflow.id} v${workflow.version} ${draft.name}"; return write(workflow.id)
        }
        override suspend fun delete(accountId: String, workflowId: String): WorkflowWrite<Unit> { calls += "delete $workflowId"; return write(Unit) }
        private fun <T> write(value: T): WorkflowWrite<T> = if (held) WorkflowWrite.Held("c1", "https://ostrom.example.org/confirm/c1", 5L) else WorkflowWrite.Applied(value)
    }

    private fun access(features: Set<String> = setOf(Features.WORKFLOWS)) = WorkflowAccess(NoRuns(), FakeFeatures(features), fakeGuard())

    @Test fun aPlayedDraftWithEveryRuleKeptHasNoProblem() {
        assertEquals(emptyList(), WorkflowChecks.check(playedDraft(), listOf(callee("other", "Other")), "wf1"))
        assertTrue(WorkflowChecks.runsOnServer(playedDraft()))
    }

    @Test fun aNewDraftAsksForANameATitleAndInstructions() {
        assertEquals(setOf(ProblemRule.NAME, ProblemRule.TITLE, ProblemRule.AGENT_INSTRUCTION), rules(WorkflowEdits.newDraft()))
    }

    @Test fun aHarnessOnlyWorkflowOnlyNeedsAName() {
        val harness = WorkflowDraft("Gate", nodes = listOf(DraftNode("g", NodeKind.GATE, ""), DraftNode("g", NodeKind.STEP, "")), edges = listOf(DraftEdge("g", "nowhere")))
        assertEquals(emptySet(), rules(harness))
        assertEquals(setOf(ProblemRule.NAME), rules(harness.copy(name = " ")))
        assertEquals(setOf(ProblemRule.NAME), rules(harness.copy(name = "a\u0007b")))
    }

    @Test fun theHeaderInputsAndLimitsAreCheckedAsTheServerDoes() {
        val draft = playedDraft().copy(
            description = "x".repeat(2_001), limits = DraftLimits(maxNodes = 0),
            inputs = listOf(
                WorkflowInput("1bad", "", required = false, default = null),
                WorkflowInput("long", "Long", required = false, default = "abc", maxLength = 2),
                WorkflowInput("zero", "Zero", required = false, default = null, maxLength = 0),
                WorkflowInput("dup", "A", required = false, default = null), WorkflowInput("dup", "B", required = false, default = null),
            ) + (1..12).map { WorkflowInput("i$it", "I", required = false, default = null) },
        )
        assertEquals(
            setOf(ProblemRule.DESCRIPTION, ProblemRule.LIMITS, ProblemRule.TOO_MANY_INPUTS, ProblemRule.INPUT_ID, ProblemRule.INPUT_LABEL, ProblemRule.INPUT_DEFAULT_LONG,
                ProblemRule.INPUT_MAX_LENGTH, ProblemRule.INPUT_DUPLICATE, ProblemRule.TEMPLATE_UNKNOWN),
            rules(draft),
        )
        assertEquals(setOf(ProblemRule.TOO_MANY_NODES), rules(playedDraft().copy(limits = DraftLimits(maxNodes = 1))))
    }

    @Test fun theGraphNeedsAFirstStepThatIsNotAQuestionEveryStepReachedAndAnEnd() {
        assertEquals(setOf(ProblemRule.START_MISSING), rules(playedDraft().copy(start = "gone")))
        assertEquals(setOf(ProblemRule.START_WAIT), rules(playedDraft().copy(start = "ask")).minus(ProblemRule.NOT_REACHABLE))
        val loop = playedDraft().copy(edges = listOf(DraftEdge("write", "ask", "delivered"), DraftEdge("ask", "write", "yes"), DraftEdge("ask", "write", "again")))
        assertEquals(setOf(ProblemRule.NO_ENDING), rules(loop))
        val island = playedDraft().copy(edges = listOf(DraftEdge("write", null, "delivered")))
        assertEquals(setOf(ProblemRule.NOT_REACHABLE), rules(island))
        val dangling = playedDraft().copy(edges = playedDraft().edges + DraftEdge("ask", "nowhere", "next"))
        assertTrue(ProblemRule.TRANSITION_TARGET in rules(dangling))
        val twice = playedDraft().copy(nodes = playedDraft().nodes + DraftNode("write", NodeKind.AGENT, "Again", instruction = "x"))
        assertTrue(ProblemRule.NODE_DUPLICATE in rules(twice))
    }

    @Test fun eachOutcomeGoesSomewhereAndEachEdgeTakesAnOutcome() {
        val missing = playedDraft().copy(edges = listOf(DraftEdge("write", "ask", "delivered"), DraftEdge("ask", null, "yes")))
        assertEquals(listOf("again"), WorkflowChecks.check(missing).filter { it.rule == ProblemRule.TRANSITION_MISSING }.map { it.args.first() })
        val wrong = playedDraft().copy(edges = playedDraft().edges + DraftEdge("write", null, "nope"))
        assertEquals(setOf(ProblemRule.TRANSITION_OUTCOME), rules(wrong))
        val twice = playedDraft().copy(edges = playedDraft().edges + DraftEdge("ask", null, "yes"))
        assertEquals(setOf(ProblemRule.TRANSITION_DUPLICATE), rules(twice))
        val byDefault = playedDraft().copy(edges = listOf(DraftEdge("write", "ask"), DraftEdge("ask", null)))
        assertEquals(emptySet(), rules(byDefault), "The default edge takes every outcome")
        val noEdge = playedDraft().copy(edges = listOf(DraftEdge("write", "ask")))
        assertEquals(emptySet(), rules(noEdge), "A node without edges ends the run")
        val check = DraftNode("c", NodeKind.CHECK, "Check", branches = true)
        val branches = playedDraft().copy(nodes = playedDraft().nodes + check, edges = playedDraft().edges + DraftEdge("ask", "c", "next") - DraftEdge("ask", null, "yes"))
        assertEquals(setOf("true", "false"), WorkflowChecks.check(branches).filter { it.rule == ProblemRule.TRANSITION_MISSING }.map { it.args.first() }.toSet())
    }

    @Test fun eachKindNeedsWhatItRuns() {
        fun with(node: DraftNode) = playedDraft().copy(nodes = listOf(playedDraft().nodes[0], node), edges = listOf(DraftEdge("write", "ask", "delivered")))
        val ask = playedDraft().nodes[1]
        assertEquals(setOf(ProblemRule.WAIT_QUESTION, ProblemRule.ACTION_ID, ProblemRule.ACTION_DUPLICATE, ProblemRule.ACTIONS_TOO_MANY, ProblemRule.TITLE, ProblemRule.NODE_ID, ProblemRule.MAX_VISITS),
            rules(with(ask.copy(id = "ask!", question = "", title = "", maxVisits = 21, actions = (1..9).map { DraftAction("a", "A") } + DraftAction("1x", "X")))).minus(ProblemRule.NOT_REACHABLE).minus(ProblemRule.TRANSITION_TARGET))
        val agent = playedDraft().nodes[0]
        val url = agent.copy(deliverable = DraftDeliverable("text", "x".repeat(201), "github.com", "{}"))
        assertEquals(setOf(ProblemRule.DELIVERABLE_LABEL, ProblemRule.DELIVERABLE_DOMAIN, ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON), rules(playedDraft().copy(nodes = listOf(url, ask))))
        val badSchema = agent.copy(deliverable = DraftDeliverable("json", schema = "[1]"))
        assertEquals(setOf(ProblemRule.DELIVERABLE_SCHEMA_INVALID), rules(playedDraft().copy(nodes = listOf(badSchema, ask))))
        val goodUrl = agent.copy(deliverable = DraftDeliverable("url", domain = "github.com"))
        assertEquals(emptySet(), rules(playedDraft().copy(nodes = listOf(goodUrl, ask))))
        val call = DraftNode("call", NodeKind.WORKFLOW, "Call", arguments = mapOf("1x" to "v"))
        assertEquals(setOf(ProblemRule.CALLEE_MISSING, ProblemRule.ARGUMENT_NAME), rules(playedDraft().copy(nodes = playedDraft().nodes + call, edges = playedDraft().edges + DraftEdge("ask", "call", "next"))))
    }

    @Test fun theCallsAreCheckedAgainstTheOtherWorkflows() {
        fun calling(id: String, arguments: Map<String, String> = emptyMap(), limits: DraftLimits = DraftLimits()) = playedDraft().copy(
            limits = limits,
            nodes = playedDraft().nodes + DraftNode("call", NodeKind.WORKFLOW, "Call", workflowId = id, arguments = arguments),
            edges = playedDraft().edges - DraftEdge("ask", null, "yes") + DraftEdge("ask", "call", "yes"),
        )
        val others = listOf(
            callee("needs", "Needs", listOf(WorkflowInput("v", "V", required = true, default = null))), callee("b", calls = "c"), callee("c", calls = "wf1"),
            callee("d1", calls = "d2"), callee("d2", calls = "d3"), callee("d3"),
        )
        assertEquals(setOf(ProblemRule.CALLEE_UNKNOWN), rules(calling("ghost"), others, "wf1"))
        assertEquals(setOf(ProblemRule.CALLEE_SELF), rules(calling("wf1"), others, "wf1"))
        assertEquals(listOf("wf1 -> b -> c -> wf1"), WorkflowChecks.check(calling("b"), others, "wf1").filter { it.rule == ProblemRule.CALLEE_LOOP }.map { it.args.first() })
        assertEquals(setOf(ProblemRule.CALLEE_DEPTH), rules(calling("d1", limits = DraftLimits(maxDepth = 2)), others, "wf1"))
        val diamond = listOf(callee("top", calls = "d1"), callee("d1", calls = "d3").let { it.copy(nodes = it.nodes + WorkflowNode("e", NodeKind.WORKFLOW, "E", null, "d2")) },
            callee("d2", calls = "d3"), callee("d3"))
        assertEquals(setOf(ProblemRule.CALLEE_DEPTH), rules(calling("top", limits = DraftLimits(maxDepth = 3)), diamond, "wf1"), "A shared sub-workflow counts at its deepest")
        assertEquals(emptySet(), rules(calling("d1"), others, "wf1"))
        assertEquals(setOf(ProblemRule.ARGUMENT_MISSING), rules(calling("needs"), others, "wf1"))
        assertEquals(setOf(ProblemRule.ARGUMENT_UNKNOWN), rules(calling("needs", mapOf("v" to "1", "w" to "2")), others, "wf1"))
        assertEquals(emptySet(), rules(calling("ghost"), null, "wf1"), "Without the catalogue the server makes the call checks")
        assertEquals(setOf(ProblemRule.NAME_TAKEN), rules(playedDraft().copy(name = " needs "), others, "wf1"))
        assertEquals(emptySet(), rules(playedDraft(), listOf(callee("wf1", "Release")), "wf1"), "Its own name is free")
    }

    @Test fun addingAStepRoutesTheEndToItAndKeepsTheFlowWhole() {
        val added = WorkflowEdits.addNode(playedDraft(), NodeKind.WAIT)
        assertEquals("ask2", added.nodes.last().id)
        assertEquals(DraftEdge("ask", "ask2", "yes"), added.edgeOf("ask", "yes"))
        assertEquals(DraftEdge("ask2", null, "done"), added.edgeOf("ask2", "done"))
        val call = WorkflowEdits.addNode(WorkflowDraft("x"), NodeKind.WORKFLOW)
        assertEquals("call", call.start)
        assertEquals(listOf(DraftEdge("call", null, "delivered")), call.edges)
        assertEquals("agent2", WorkflowEdits.addNode(WorkflowEdits.newDraft(), NodeKind.AGENT).nodes.last().id)
    }

    @Test fun aStepIsRenamedMovedRetypedAndRemovedWithItsEdges() {
        val renamed = WorkflowEdits.renameNode(playedDraft(), "write", "draft")
        assertEquals("draft", renamed.start)
        assertEquals(DraftEdge("draft", "ask", "delivered"), renamed.edges.first())
        assertEquals("{{nodes.draft.output}}", renamed.node("ask")?.instruction)
        assertEquals(playedDraft(), WorkflowEdits.renameNode(playedDraft(), "write", "write"))
        assertEquals(listOf("ask", "write"), WorkflowEdits.moveNode(playedDraft(), "ask", -1).nodes.map { it.id })
        assertEquals(playedDraft(), WorkflowEdits.moveNode(playedDraft(), "ask", 1))
        val retyped = WorkflowEdits.setKind(playedDraft(), "write", NodeKind.WORKFLOW)
        assertEquals(NodeKind.WORKFLOW, retyped.node("write")?.kind)
        assertEquals("Write the notes", retyped.node("write")?.title)
        assertEquals("", retyped.node("write")?.instruction)
        assertEquals(listOf(DraftEdge("write", "ask", "delivered")), retyped.edgesFrom("write"))
        assertEquals(playedDraft(), WorkflowEdits.setKind(playedDraft(), "write", NodeKind.AGENT))
        val removed = WorkflowEdits.removeNode(playedDraft(), "write")
        assertEquals("ask", removed.start)
        assertEquals(listOf(DraftEdge("ask", null, "yes"), DraftEdge("ask", "ask", "again")), removed.edges, "What led to it goes where it went")
        val chain = WorkflowEdits.addNode(WorkflowEdits.addNode(WorkflowEdits.newDraft(), NodeKind.AGENT), NodeKind.AGENT)
        assertEquals(listOf(DraftEdge("agent", "agent3", "delivered"), DraftEdge("agent3", null, "delivered")), WorkflowEdits.removeNode(chain, "agent2").edges)
    }

    @Test fun aRenameNeverMergesTwoEntriesNorTouchesAnotherName() {
        val two = WorkflowEdits.addNode(WorkflowEdits.newDraft(), NodeKind.AGENT)
        assertEquals(two, WorkflowEdits.renameNode(two, "agent2", "agent"), "An id another step has is not taken")
        val inputs = playedDraft().copy(
            inputs = listOf(WorkflowInput("a", "A", required = false, default = null), WorkflowInput("ab", "AB", required = false, default = null)),
            nodes = playedDraft().nodes.map { if (it.id == "write") it.copy(instruction = "{{inputs.a}} {{ inputs.a }} {{inputs.ab}}", deliverable = DraftDeliverable("text", "{{inputs.a}}")) else it },
        )
        val renamed = WorkflowEdits.renameInput(inputs, "a", "x")
        assertEquals("{{inputs.x}} {{ inputs.x }} {{inputs.ab}}", renamed.node("write")?.instruction)
        assertEquals("{{inputs.x}}", renamed.node("write")?.deliverable?.label)
        assertEquals(inputs, WorkflowEdits.renameInput(inputs, "a", "ab"))
        assertEquals(playedDraft(), WorkflowEdits.renameAction(playedDraft(), "ask", "again", "yes"))
        val retyped = WorkflowEdits.setKind(playedDraft(), "ask", NodeKind.AGENT)
        assertEquals(listOf(DraftEdge("ask", null, "delivered")), retyped.edgesFrom("ask"))
        val byDefault = playedDraft().copy(edges = listOf(DraftEdge("write", "ask"), DraftEdge("ask", null)))
        assertEquals(listOf(DraftEdge("write", "ask")), WorkflowEdits.setKind(byDefault, "write", NodeKind.WAIT).edgesFrom("write"))
    }

    @Test fun aProblemOfTheGraphIsShownAgainstItsStep() {
        val island = playedDraft().copy(edges = listOf(DraftEdge("write", null, "delivered"), DraftEdge("write", "ghost", "next")))
        val problems = WorkflowChecks.check(island)
        assertEquals("ask", problems.single { it.rule == ProblemRule.NOT_REACHABLE }.nodeId)
        assertEquals("write", problems.single { it.rule == ProblemRule.TRANSITION_TARGET }.nodeId)
        assertEquals("ask", WorkflowChecks.check(playedDraft().copy(start = "ask")).single { it.rule == ProblemRule.START_WAIT }.nodeId)
    }

    @Test fun anOutcomeIsRoutedAndAnInputOrAButtonRenamedWithWhatNamesIt() {
        val routed = WorkflowEdits.route(playedDraft(), "ask", "again", null)
        assertEquals(DraftEdge("ask", null, "again"), routed.edgeOf("ask", "again"))
        assertEquals(DraftEdge("x", "y", null), WorkflowEdits.route(WorkflowDraft("n"), "x", DraftNode.NEXT, "y").edges.single())
        val input = WorkflowEdits.newInput(playedDraft())
        assertEquals("input", input.id)
        val renamed = WorkflowEdits.renameInput(playedDraft(), "version", "tag")
        assertEquals("tag", renamed.inputs.single().id)
        assertEquals("Notes for {{inputs.tag}}", renamed.node("write")?.instruction)
        val first = WorkflowEdits.addAction(WorkflowEdits.addNode(WorkflowDraft("n"), NodeKind.WAIT), "ask")
        assertEquals(listOf("action"), first.node("ask")?.actions?.map { it.id })
        assertEquals(listOf(DraftEdge("ask", null, "action")), first.edgesFrom("ask"))
        val renamedAction = WorkflowEdits.renameAction(playedDraft(), "ask", "again", "retry")
        assertEquals(DraftEdge("ask", "write", "retry"), renamedAction.edgeOf("ask", "retry"))
        val removedAction = WorkflowEdits.removeAction(playedDraft(), "ask", "again")
        assertEquals(listOf("yes"), removedAction.node("ask")?.actions?.map { it.id })
        assertEquals(listOf(DraftEdge("ask", null, "yes")), removedAction.edgesFrom("ask"))
        assertEquals(listOf("{{inputs.version}}", "{{nodes.write.output}}"), WorkflowEdits.templates(playedDraft(), "ask"))
    }

    @Test fun theOutcomesFollowTheKind() {
        assertEquals(listOf("delivered"), playedDraft().nodes[0].outcomes)
        assertEquals(listOf("yes", "again"), playedDraft().nodes[1].outcomes)
        assertEquals(listOf("done"), DraftNode("w", NodeKind.WAIT, "W").outcomes)
        assertEquals(listOf("true", "false"), DraftNode("c", NodeKind.CHECK, "C", branches = true).outcomes)
        assertEquals(listOf("next"), DraftNode("c", NodeKind.CHECK, "C").outcomes)
        assertEquals(listOf("next"), DraftNode("g", NodeKind.GATE, "G").outcomes)
        assertFalse(DraftNode("g", NodeKind.GATE, "G").editable)
        assertNull(WorkflowDraft("n", nodes = listOf(DraftNode("c", NodeKind.CHECK, "C", branches = true)), edges = listOf(DraftEdge("c", null))).edgeOf("c", "true"))
    }

    @Test fun theUseCasesAreRefusedByAServerWithoutWorkflows() = runTest {
        val editor = FakeEditor()
        val access = access(emptySet())
        assertFailsWith<AppException.Unsupported> { LoadWorkflowEditRightsUseCase(access, editor)("a1") }
        assertFailsWith<AppException.Unsupported> { LoadEditableWorkflowUseCase(access, editor)("a1", "wf1") }
        assertFailsWith<AppException.Unsupported> { SaveWorkflowUseCase(access, editor).create("a1", playedDraft(), null) }
        assertFailsWith<AppException.Unsupported> { DeleteWorkflowUseCase(access, editor)("a1", "wf1") }
        assertEquals(emptyList(), editor.calls)
    }

    @Test fun aDraftThatBreaksARuleIsRefusedWithoutBeingSent() = runTest {
        val editor = FakeEditor()
        val save = SaveWorkflowUseCase(access(), editor)
        val refused = assertFailsWith<AppException.WorkflowInvalid> { save.create("a1", WorkflowEdits.newDraft(), null) }
        assertEquals(setOf(ProblemRule.NAME, ProblemRule.TITLE, ProblemRule.AGENT_INSTRUCTION), refused.problems.map { it.rule }.toSet())
        assertFailsWith<AppException.WorkflowInvalid> { save.update("a1", editor.editable, playedDraft().copy(name = ""), null) }
        assertEquals(emptyList(), editor.calls)
    }

    @Test fun aDraftIsCreatedSavedOverItsVersionDeletedOrHeld() = runTest {
        val editor = FakeEditor()
        val access = access()
        val save = SaveWorkflowUseCase(access, editor)
        assertEquals(WorkflowWrite.Applied("wf9"), save.create("a1", playedDraft(), emptyList()))
        assertEquals(WorkflowWrite.Applied("wf1"), save.update("a1", editor.editable, playedDraft().copy(name = "Ship"), emptyList()))
        assertEquals(WorkflowWrite.Applied(Unit), DeleteWorkflowUseCase(access, editor)("a1", "wf1"))
        editor.held = true
        assertEquals(WorkflowWrite.Held("c1", "https://ostrom.example.org/confirm/c1", 5L), DeleteWorkflowUseCase(access, editor)("a1", "wf1"))
        assertEquals(WorkflowEditRights(true, "admins", 0), LoadWorkflowEditRightsUseCase(access, editor)("a1"))
        assertEquals(editor.editable, LoadEditableWorkflowUseCase(access, editor)("a1", "wf1"))
        assertFailsWith<AppException.NotFound> { LoadEditableWorkflowUseCase(access, editor)("a1", "") }
        assertFailsWith<AppException.NotFound> { DeleteWorkflowUseCase(access, editor)("a1", " ") }
        assertEquals(listOf("create Release", "save wf1 v3 Ship", "delete wf1", "delete wf1", "rights", "get wf1"), editor.calls)
    }

    @Test fun aWorkflowWithAKindThisAppDoesNotKnowIsNotEdited() = runTest {
        val unknown = playedDraft().copy(nodes = playedDraft().nodes + DraftNode("x", NodeKind.UNKNOWN, "Future"))
        val editor = FakeEditor(EditableWorkflow("wf1", 1, unknown))
        assertFailsWith<AppException.Unsupported> { LoadEditableWorkflowUseCase(access(), editor)("a1", "wf1") }
    }

    @Test fun aConfirmationOpensOnlyOnTheAccountsOwnServer() {
        val origin = "https://ostrom.example.org"
        assertEquals("$origin/confirm/c1", confirmationLink(origin, "$origin/confirm/c1"))
        assertEquals("$origin/confirm/c1", confirmationLink("$origin/", "$origin/confirm/c1"))
        assertEquals(origin, confirmationLink(origin, origin))
        assertNull(confirmationLink(origin, "https://ostrom.example.org.evil.test/confirm/c1"))
        assertNull(confirmationLink(origin, "https://evil.test/confirm/c1"))
        assertNull(confirmationLink(origin, "http://ostrom.example.org/confirm/c1"))
        assertNull(confirmationLink(origin, "$origin/confirm/c1 x"))
        assertNull(confirmationLink("", "/confirm/c1"))
    }
}
