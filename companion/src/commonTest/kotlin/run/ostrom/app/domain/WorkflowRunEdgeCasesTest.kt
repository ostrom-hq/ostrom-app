// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.usecases.workflows.concernsRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowRunEdgeCasesTest {
    @Test
    fun aRunEventConcernsTheRunOrOneOfItsSubWorkflowsRuns() {
        val run = workflowRun("r1", visits = listOf(visit(1, childRunId = "child"), visit(2)))
        assertTrue(OrgChange(OrgChange.RUN_UPDATED, "child", "m1").concernsRun(run, "r1"))
        assertFalse(OrgChange(OrgChange.RUN_UPDATED, "other", "m1").concernsRun(run, "r1"))
        assertFalse(OrgChange(OrgChange.WAIT_CREATED, "r1", "m1").concernsRun(run, "r1"))
        assertFalse(OrgChange(OrgChange.RUN_UPDATED, "child", "m1").concernsRun(workflowRun("r1"), "r1"))
    }

    @Test
    fun theCurrentTitleIsTheActiveVisitsElseTheNodeOfARunStillMoving() {
        assertEquals("Node 2", workflowRun("r1", visits = listOf(visit(1, status = "done", endedAt = 2), visit(2))).currentTitle)
        assertEquals("build", workflowRun("r1", visits = listOf(visit(1, status = "done", endedAt = 2))).currentTitle)
        assertNull(workflowRun("r1", status = RunStatus.SUCCEEDED).currentTitle)
        assertNull(workflowRun("r1").copy(currentNodeId = null).currentTitle)
    }

    @Test
    fun aWorkflowsNodeIsFoundByItsId() {
        val node = WorkflowNode("a", NodeKind.AGENT, "A", null)
        val workflow = Workflow("wf1", "Release", "d", null, 1, emptyList(), "accepted", "a", emptyList(), listOf(node), null)
        assertEquals(node, workflow.node("a"))
        assertNull(workflow.node("b"))
    }

    @Test
    fun anOutcomeGoesToItsOwnEdgeElseTheDefaultOneExceptOutOfABranchingCheck() {
        val draft = WorkflowDraft(
            "Flow",
            nodes = listOf(DraftNode("check", NodeKind.CHECK, "Check", branches = true), DraftNode("step", NodeKind.STEP, "Step")),
            edges = listOf(DraftEdge("check", "step", "true"), DraftEdge("check", "step"), DraftEdge("step", null)),
        )
        assertEquals(DraftEdge("check", "step", "true"), draft.edgeOf("check", "true"))
        assertNull(draft.edgeOf("check", "false"))
        assertEquals(DraftEdge("step", null), draft.edgeOf("step", "anything"))
        assertNull(draft.edgeOf("ghost", "next"))
        assertEquals("check", draft.first)
        assertEquals("step", draft.copy(start = "step").first)
        assertNull(WorkflowDraft("Empty").first)
    }
}
