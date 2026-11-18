// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import run.ostrom.app.domain.models.DraftAction
import run.ostrom.app.domain.models.DraftDeliverable
import run.ostrom.app.domain.models.DraftEdge
import run.ostrom.app.domain.models.DraftLimits
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.ProblemRule
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.models.WorkflowProblem
import run.ostrom.app.domain.usecases.workflows.WorkflowChecks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowCheckEdgeCasesTest {
    private fun problems(draft: WorkflowDraft, others: List<Workflow>? = null, selfId: String? = null) = WorkflowChecks.check(draft, others, selfId)
    private fun rules(draft: WorkflowDraft, others: List<Workflow>? = null, selfId: String? = null) = problems(draft, others, selfId).map { it.rule }.toSet()

    private fun workflow(id: String, inputs: List<WorkflowInput> = emptyList(), vararg calls: String) = Workflow(
        id, id, "d", null, 1, emptyList(), "accepted", null, inputs,
        listOf(WorkflowNode("a", NodeKind.AGENT, "A", null)) + calls.map { WorkflowNode("c-$it", NodeKind.WORKFLOW, "C", null, it) }, null,
    )

    @Test
    fun aNameWithAControlCharacterOrTooLongIsRefusedEvenForADraftTheServerDoesNotPlay() {
        assertEquals(setOf(ProblemRule.NAME), rules(WorkflowDraft("Ship\u0007")))
        assertEquals(setOf(ProblemRule.NAME), rules(WorkflowDraft("x".repeat(121))))
        assertTrue(rules(WorkflowDraft("Ship")).isEmpty())
    }

    @Test
    fun eachLimitIsCheckedAgainstItsRange() {
        val played = playedDraft()
        assertTrue(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxNodes = 0))))
        assertTrue(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxVisits = 501))))
        assertTrue(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxDepth = -1))))
        assertFalse(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxNodes = 100, maxVisits = 500, maxDepth = 0))))
        // Out of range, the node count is not compared with it.
        assertFalse(ProblemRule.TOO_MANY_NODES in rules(played.copy(limits = DraftLimits(maxNodes = 0))))
    }

    @Test
    fun anInputIsNamedByItsIdOrAQuestionMarkWhenItHasNone() {
        val draft = playedDraft().copy(inputs = listOf(WorkflowInput("", "", required = false, default = null, maxLength = 0)))
        val found = problems(draft).filter { it.nodeId == null && it.rule in setOf(ProblemRule.INPUT_ID, ProblemRule.INPUT_LABEL, ProblemRule.INPUT_MAX_LENGTH) }
        assertEquals(setOf(ProblemRule.INPUT_ID, ProblemRule.INPUT_LABEL, ProblemRule.INPUT_MAX_LENGTH), found.map { it.rule }.toSet())
        assertTrue(found.all { it.args.first() == "?" })
        val long = playedDraft().copy(inputs = listOf(WorkflowInput("v", "x".repeat(201), required = false, default = null)))
        assertTrue(ProblemRule.INPUT_LABEL in rules(long))
    }

    @Test
    fun anEdgeFromAnUnknownStepToAnUnknownOneIsShownAgainstNoStep() {
        val draft = playedDraft().copy(edges = playedDraft().edges + DraftEdge("ghost", "nowhere"))
        val problem = problems(draft).single { it.rule == ProblemRule.TRANSITION_TARGET }
        assertEquals(WorkflowProblem(ProblemRule.TRANSITION_TARGET, null, listOf("ghost", "nowhere")), problem)
    }

    @Test
    fun aDraftWithoutStepsHasNoGraphToCheck() {
        assertEquals(emptyList(), problems(WorkflowDraft("Empty")))
    }

    @Test
    fun aStartThatWaitsIsNamedByItsIdWhenUntitled() {
        val waitFirst = playedDraft().copy(start = "ask", nodes = playedDraft().nodes.map { if (it.id == "ask") it.copy(title = " ") else it })
        val problem = problems(waitFirst).single { it.rule == ProblemRule.START_WAIT }
        assertEquals(listOf("ask"), problem.args)
        assertTrue(problems(waitFirst).any { it.rule == ProblemRule.TITLE && it.nodeId == "ask" })
    }

    @Test
    fun aStepVisitedZeroTimesIsRefused() {
        val draft = playedDraft().copy(nodes = playedDraft().nodes.map { if (it.id == "write") it.copy(maxVisits = 0) else it })
        assertTrue(problems(draft).any { it.rule == ProblemRule.MAX_VISITS && it.nodeId == "write" })
        assertFalse(ProblemRule.MAX_VISITS in rules(playedDraft().copy(nodes = playedDraft().nodes.map { it.copy(maxVisits = 20) })))
    }

    @Test
    fun aTemplateMayNameAnInputOrAnotherStepsOutputButNothingElse() {
        fun unknown(instruction: String) = problems(playedDraft().copy(nodes = playedDraft().nodes.map { if (it.id == "write") it.copy(instruction = instruction) else it }))
            .filter { it.rule == ProblemRule.TEMPLATE_UNKNOWN }.map { it.args.single() }
        assertEquals(emptyList(), unknown("{{ inputs.version }} after {{nodes.ask.output}}"))
        assertEquals(listOf("inputs.missing", "nodes.write.output", "nodes.ghost.output", "secrets.token"),
            unknown("{{inputs.missing}} {{nodes.write.output}} {{nodes.ghost.output}} {{secrets.token}}"))
    }

    @Test
    fun aDeliverablesDomainAndSchemaFitOnlyItsType() {
        fun found(deliverable: DraftDeliverable) = rules(playedDraft().copy(nodes = playedDraft().nodes.map { if (it.id == "write") it.copy(deliverable = deliverable) else it }))
        assertTrue(ProblemRule.DELIVERABLE_DOMAIN in found(DraftDeliverable("text", domain = "example.org")))
        assertTrue(ProblemRule.DELIVERABLE_DOMAIN in found(DraftDeliverable("url", domain = "not a domain")))
        assertFalse(ProblemRule.DELIVERABLE_DOMAIN in found(DraftDeliverable("url", domain = "docs.example.org")))
        assertFalse(ProblemRule.DELIVERABLE_DOMAIN in found(DraftDeliverable("url", domain = "")))
        assertTrue(ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON in found(DraftDeliverable("text", schema = "{}")))
        assertTrue(ProblemRule.DELIVERABLE_SCHEMA_INVALID in found(DraftDeliverable("json", schema = "[1]")))
        assertTrue(ProblemRule.DELIVERABLE_SCHEMA_INVALID in found(DraftDeliverable("json", schema = "{oops")))
        val fine = found(DraftDeliverable("json", schema = """{"type":"object"}""", label = "Notes"))
        assertFalse(ProblemRule.DELIVERABLE_SCHEMA_INVALID in fine || ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON in fine)
        assertFalse(ProblemRule.DELIVERABLE_SCHEMA_INVALID in found(DraftDeliverable("json", schema = "  ")))
    }

    @Test
    fun aWaitWithoutQuestionOrWithAnUnnamedButtonIsRefused() {
        val draft = playedDraft().copy(nodes = playedDraft().nodes.map {
            if (it.id == "ask") it.copy(question = " ", actions = listOf(DraftAction("yes", "Yes"), DraftAction("again", "Again"), DraftAction("", "Blank"))) else it
        })
        val found = problems(draft)
        assertTrue(found.any { it.rule == ProblemRule.WAIT_QUESTION && it.nodeId == "ask" })
        assertEquals(listOf("?"), found.single { it.rule == ProblemRule.ACTION_ID }.args)
    }

    @Test
    fun onlyACallStepWithAWorkflowIsCheckedAgainstTheOthers() {
        val others = listOf(workflow("other"))
        // An agent step that names a workflow, and a call step with a blank one, call nothing.
        val draft = playedDraft().copy(nodes = playedDraft().nodes + DraftNode("misc", NodeKind.STEP, "Misc", workflowId = "ghost") +
            DraftNode("call", NodeKind.WORKFLOW, "Call", workflowId = " "))
        val found = rules(draft, others, "wf1")
        assertFalse(ProblemRule.CALLEE_UNKNOWN in found)
        assertTrue(ProblemRule.CALLEE_MISSING in found)
    }

    @Test
    fun aRequiredInputWithADefaultOrGivenNeedsNoArgument() {
        val inputs = listOf(
            WorkflowInput("given", "Given", required = true, default = null),
            WorkflowInput("defaulted", "Defaulted", required = true, default = "x"),
            WorkflowInput("optional", "Optional", required = false, default = null),
            WorkflowInput("needed", "Needed", required = true, default = null),
        )
        val draft = playedDraft().copy(
            nodes = playedDraft().nodes + DraftNode("call", NodeKind.WORKFLOW, "Call", workflowId = "lib", arguments = mapOf("given" to "1")),
            edges = playedDraft().edges + DraftEdge("call", null, "delivered"),
        )
        val missing = problems(draft, listOf(workflow("lib", inputs)), "wf1").filter { it.rule == ProblemRule.ARGUMENT_MISSING }
        assertEquals(listOf(listOf("needed", "lib")), missing.map { it.args })
    }

    @Test
    fun aChainThatCallsAWorkflowOutsideTheListStopsThere() {
        val draft = playedDraft().copy(
            nodes = playedDraft().nodes + DraftNode("call", NodeKind.WORKFLOW, "Call", workflowId = "lib"),
            edges = playedDraft().edges + DraftEdge("call", null, "delivered"),
        )
        // lib calls a workflow this member cannot see: the depth counts lib and that one, no loop, nothing unknown below.
        val found = rules(draft, listOf(workflow("lib", emptyList(), "hidden")), "wf1")
        assertFalse(ProblemRule.CALLEE_DEPTH in found)
        assertFalse(ProblemRule.CALLEE_LOOP in found)
    }

    @Test
    fun eachLimitIsRefusedAboveItsRangeToo() {
        val played = playedDraft()
        assertTrue(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxNodes = 101))))
        assertTrue(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxVisits = 0))))
        assertTrue(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxDepth = 6))))
        assertFalse(ProblemRule.LIMITS in rules(played.copy(limits = DraftLimits(maxNodes = 1, maxVisits = 1, maxDepth = 5))))
    }

    @Test
    fun anInputLongerThanTheServerTakesIsRefused() {
        val input = WorkflowInput("v", "Version", required = false, default = null, maxLength = 32_001)
        assertTrue(ProblemRule.INPUT_MAX_LENGTH in rules(playedDraft().copy(inputs = listOf(input))))
        assertFalse(ProblemRule.INPUT_MAX_LENGTH in rules(playedDraft().copy(inputs = listOf(input.copy(maxLength = 32_000)))))
    }

    @Test
    fun anEdgeFromAKnownStepToAnUnknownOneIsShownAgainstItsStep() {
        val draft = playedDraft().copy(edges = playedDraft().edges + DraftEdge("write", "nowhere", "failed"))
        assertEquals(WorkflowProblem(ProblemRule.TRANSITION_TARGET, "write", listOf("write", "nowhere")), problems(draft).single { it.rule == ProblemRule.TRANSITION_TARGET })
    }

    @Test
    fun withoutAStartTheServerPlaysTheFirstStep() {
        assertTrue(problems(playedDraft().copy(start = null)).isEmpty())
        val waitFirst = playedDraft().copy(start = null, nodes = playedDraft().nodes.reversed())
        assertEquals(listOf("Approve"), problems(waitFirst).single { it.rule == ProblemRule.START_WAIT }.args)
    }

    @Test
    fun aTitleOrAQuestionLongerThanTheServerTakesIsRefused() {
        val long = "x".repeat(201)
        val draft = playedDraft().copy(nodes = playedDraft().nodes.map { if (it.id == "ask") it.copy(title = long, question = long) else it })
        val found = problems(draft).filter { it.nodeId == "ask" }.map { it.rule }.toSet()
        assertTrue(ProblemRule.TITLE in found)
        assertTrue(ProblemRule.WAIT_QUESTION in found)
        val blank = playedDraft().copy(nodes = playedDraft().nodes.map { if (it.id == "ask") it.copy(question = " ") else it })
        assertTrue(problems(blank).any { it.rule == ProblemRule.WAIT_QUESTION && it.nodeId == "ask" })
    }
}
