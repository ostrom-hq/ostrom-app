// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import run.ostrom.app.domain.models.DraftAction
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.usecases.workflows.WorkflowEdits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class WorkflowEditEdgeCasesTest {
    private val ask = DraftNode("ask", NodeKind.WAIT, "Ask", question = "OK?")
    private val agent = DraftNode("agent", NodeKind.AGENT, "Write", instruction = "Use {{nodes.ask.output}}", deliverable = DraftDeliverable("text"))
    private val draft = WorkflowDraft("Ship", start = "ask", nodes = listOf(ask, agent), edges = listOf(DraftEdge("ask", "agent", "done"), DraftEdge("agent", null, "delivered")))

    @Test
    fun aNodeWithoutOutcomeOfItsOwnEndsTheWorkflowOnNext() {
        val added = WorkflowEdits.addNode(WorkflowDraft("Empty"), NodeKind.STEP)
        assertEquals(listOf(DraftEdge("agent", null, null)), added.edges)
        assertEquals("agent", added.start)
    }

    @Test
    fun changesToANodeThatIsNotThereLeaveTheDraftAsItIs() {
        assertSame(draft, WorkflowEdits.setKind(draft, "missing", NodeKind.AGENT))
        assertSame(draft, WorkflowEdits.addAction(draft, "missing"))
        assertSame(draft, WorkflowEdits.renameAction(draft, "missing", "a", "b"))
        assertSame(draft, WorkflowEdits.moveNode(draft, "missing", 1))
        assertSame(draft, WorkflowEdits.renameNode(draft, "missing", "other"))
        assertSame(draft, WorkflowEdits.renameInput(draft, "missing", "other"))
    }

    @Test
    fun aNodeIsNotMovedPastEitherEnd() {
        assertSame(draft, WorkflowEdits.moveNode(draft, "ask", -1))
        assertSame(draft, WorkflowEdits.moveNode(draft, "agent", 1))
        assertEquals(listOf("agent", "ask"), WorkflowEdits.moveNode(draft, "agent", -1).nodes.map { it.id })
    }

    @Test
    fun removingANodeThatOnlyLoopsOnItselfEndsTheEdgesThatLedToIt() {
        val looping = draft.copy(edges = listOf(DraftEdge("ask", "agent", "done"), DraftEdge("agent", "agent", "delivered")))
        val removed = WorkflowEdits.removeNode(looping, "agent")
        assertEquals(listOf(DraftEdge("ask", null, "done")), removed.edges)
        assertEquals("ask", removed.start)
    }

    @Test
    fun removingTheLastNodeLeavesNoStart() {
        val single = WorkflowDraft("One", start = "ask", nodes = listOf(ask))
        assertNull(WorkflowEdits.removeNode(single, "ask").start)
        assertEquals("ask", WorkflowEdits.removeNode(draft.copy(start = null), "agent").start)
    }

    @Test
    fun renamingANodeToANameTakenOrTheSameChangesNothing() {
        assertSame(draft, WorkflowEdits.renameNode(draft, "ask", "agent"))
        assertSame(draft, WorkflowEdits.renameNode(draft, "ask", "ask"))
        val twice = draft.copy(nodes = draft.nodes + ask)
        assertSame(twice, WorkflowEdits.renameNode(twice, "ask", "question"))
    }

    @Test
    fun renamingANodeFollowsInLabelsAndKeepsAnotherStart() {
        val labelled = draft.copy(
            start = "agent",
            nodes = listOf(ask, agent.copy(deliverable = DraftDeliverable("text", label = "From {{ nodes.ask.output }}"), arguments = mapOf("x" to "{{nodes.ask.output}}"))),
        )
        val renamed = WorkflowEdits.renameNode(labelled, "ask", "question")
        assertEquals("agent", renamed.start)
        assertEquals("From {{ nodes.question.output }}", renamed.node("agent")!!.deliverable!!.label)
        assertEquals(mapOf("x" to "{{nodes.question.output}}"), renamed.node("agent")!!.arguments)
        assertNull(renamed.node("question")!!.deliverable)
        assertEquals("Use {{nodes.question.output}}", renamed.node("agent")!!.instruction)
    }

    @Test
    fun renamingAnInputToAnIdTakenOrTheSameChangesNothing() {
        val inputs = draft.copy(inputs = listOf(WorkflowInput("repo", "Repo", true, null), WorkflowInput("branch", "Branch", false, "main")))
        assertSame(inputs, WorkflowEdits.renameInput(inputs, "repo", "branch"))
        assertSame(inputs, WorkflowEdits.renameInput(inputs, "repo", "repo"))
        val labelled = inputs.copy(nodes = listOf(ask, agent.copy(deliverable = DraftDeliverable("text", label = "{{inputs.repo}}"))))
        val renamed = WorkflowEdits.renameInput(labelled, "repo", "repository")
        assertEquals(listOf("repository", "branch"), renamed.inputs.map { it.id })
        assertEquals("{{inputs.repository}}", renamed.node("agent")!!.deliverable!!.label)
    }

    @Test
    fun aSecondButtonKeepsTheFirstsEdgesAndAButtonOfAnUnroutedNodeEndsTheWorkflow() {
        val once = WorkflowEdits.addAction(draft, "ask")
        assertEquals(listOf(DraftEdge("ask", "agent", "action")), once.edgesFrom("ask"))
        val twice = WorkflowEdits.addAction(once, "ask")
        assertEquals(listOf("action", "action2"), twice.node("ask")!!.actions.map { it.id })
        assertEquals(listOf(DraftEdge("ask", "agent", "action"), DraftEdge("ask", "agent", "action2")), twice.edgesFrom("ask"))
        assertEquals(agent, twice.node("agent"))

        val unrouted = WorkflowEdits.addAction(draft.copy(edges = emptyList()), "ask")
        assertEquals(listOf(DraftEdge("ask", null, "action")), unrouted.edges)
    }

    @Test
    fun aButtonIsNotRenamedToAnIdTakenOrWhenItIsNotThere() {
        val buttons = draft.copy(nodes = listOf(ask.copy(actions = listOf(DraftAction("yes", "Yes"), DraftAction("no", "No"))), agent))
        assertSame(buttons, WorkflowEdits.renameAction(buttons, "ask", "yes", "no"))
        assertSame(buttons, WorkflowEdits.renameAction(buttons, "ask", "yes", "yes"))
        assertSame(buttons, WorkflowEdits.renameAction(buttons, "ask", "maybe", "perhaps"))
    }
}
