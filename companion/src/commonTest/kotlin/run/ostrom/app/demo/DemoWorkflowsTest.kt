// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FixedClock
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.domain.models.WorkflowWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The demo's workflows, their runs, and the editor: started, cancelled, created, saved and deleted from memory. */
class DemoWorkflowsTest {
    private val clock = FixedClock(20_000_000L)
    private val demo = DemoMode(clock).also { it.start() }
    private val workflows = DemoWorkflowRepository(demo, clock)
    private val editor = DemoWorkflowEditorRepository(demo, clock)
    private val account = DemoContent.ACCOUNT_ID

    @Test fun theWorkflowsAreTheBuiltInOnesAndOneIsFoundById() = runTest {
        assertEquals(listOf(DemoWorkflowIds.RELEASE, DemoWorkflowIds.TRIAGE), workflows.workflows(account).map { it.id })
        assertEquals("Release the engine", workflows.workflow(account, DemoWorkflowIds.RELEASE).name)
        assertFailsWith<AppException.NotFound> { workflows.workflow(account, "ghost") }
    }

    @Test fun theRunsAreFilteredByWorkflowAndStatusAndAreOnePage() = runTest {
        val all = workflows.runs(account, null, null, null).runs
        assertEquals(setOf(DemoWorkflowIds.RUN_WAITING, DemoWorkflowIds.RUN_DONE, DemoWorkflowIds.RUN_CANCELLED), all.map { it.id }.toSet())
        assertEquals(all, workflows.runs(account, DemoWorkflowIds.RELEASE, null, null).runs)
        assertTrue(workflows.runs(account, DemoWorkflowIds.TRIAGE, null, null).runs.isEmpty())
        assertEquals(listOf(DemoWorkflowIds.RUN_WAITING), workflows.runs(account, null, "waiting", null).runs.map { it.id })
        assertTrue(workflows.runs(account, null, null, "c").runs.isEmpty())
        assertEquals(DemoWorkflowIds.RUN_DONE, workflows.run(account, DemoWorkflowIds.RUN_DONE).id)
        assertFailsWith<AppException.NotFound> { workflows.run(account, "ghost") }
    }

    @Test fun aRunStartsOnTheFirstNodeAfterTheTriggerAndComesFirstInTheList() = runTest {
        val started = workflows.start(account, DemoWorkflowIds.RELEASE, mapOf("version" to "2.0.0"), "k1")
        assertEquals("demo-run-started-1", started.id)
        assertEquals(RunStatus.RUNNING, started.status)
        assertEquals("build", started.currentNodeId)
        assertEquals(NodeKind.STEP, started.currentNodeKind)
        assertEquals(mapOf("version" to "2.0.0"), started.inputs)
        assertEquals("analytical-engines/engine", started.repository)
        assertEquals(clock.now, started.startedAt)
        assertEquals("ada", started.startedBy)
        assertEquals(started.id, workflows.runs(account, null, null, null).runs.first().id)
        assertEquals("demo-run-started-2", workflows.start(account, DemoWorkflowIds.RELEASE, emptyMap(), "k2").id)
    }

    @Test fun aWorkflowThatIsNotAcceptedCannotStartAndANeverSeenOneIsNotFound() = runTest {
        assertEquals(WorkflowRefusal.NOT_RUNNABLE, assertFailsWith<AppException.WorkflowRefused> { workflows.start(account, DemoWorkflowIds.TRIAGE, emptyMap(), "k") }.reason)
        assertFailsWith<AppException.NotFound> { workflows.start(account, "ghost", emptyMap(), "k") }
    }

    @Test fun aWorkflowWithOnlyATriggerStartsOnTheTriggerAndWithoutARepository() = runTest {
        val id = (editor.create(account, WorkflowDraft(name = "Only", nodes = listOf(DraftNode("t", NodeKind.TRIGGER, "Go")))) as WorkflowWrite.Applied).value
        val started = workflows.start(account, id, emptyMap(), "k")
        assertEquals("t", started.currentNodeId)
        assertNull(started.repository)
        assertEquals(1, started.workflowVersion)
    }

    @Test fun cancellingARunEndsItAndFailsTheVisitUnderWay() = runTest {
        val cancelled = workflows.cancel(account, DemoWorkflowIds.RUN_WAITING)
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertEquals(clock.now, cancelled.endedAt)
        assertNull(cancelled.currentNodeId)
        val run = workflows.run(account, DemoWorkflowIds.RUN_WAITING)
        assertEquals(RunStatus.CANCELLED, run.status)
        val last = run.visits.last()
        assertEquals("failed" to "cancelled", last.status to last.outcome)
        assertEquals(setOf("done"), run.visits.dropLast(1).map { it.status }.toSet(), "finished visits are untouched")
        assertEquals(RunStatus.SUCCEEDED, workflows.run(account, DemoWorkflowIds.RUN_DONE).status, "other runs are not touched")
    }

    @Test fun anEndedRunCannotBeCancelledAndAnUnknownOneIsNotFound() = runTest {
        assertEquals(WorkflowRefusal.RUN_ENDED, assertFailsWith<AppException.WorkflowRefused> { workflows.cancel(account, DemoWorkflowIds.RUN_DONE) }.reason)
        assertFailsWith<AppException.NotFound> { workflows.cancel(account, "ghost") }
    }

    @Test fun theEditorAnswersWhoMayDecideAndOpensABuiltInWorkflowNodeByNode() = runTest {
        val rights = editor.rights(account)
        assertEquals(Triple(true, "admins", 0), Triple(rights.mayDecide, rights.mode, rights.memberCount))
        val release = editor.editable(account, DemoWorkflowIds.RELEASE)
        assertEquals(3, release.version)
        assertEquals(listOf("trigger", "build", "agent", "merge", "tag"), release.draft.nodes.map { it.id })
        assertEquals("Summarize the merged pull requests since the last tag.", release.draft.node("agent")?.instruction)
        assertEquals("", release.draft.node("build")?.instruction)
        assertEquals("text", release.draft.node("agent")?.deliverable?.type)
        assertNull(release.draft.node("build")?.deliverable)
        assertEquals(listOf("build", "agent", "merge", "tag", null), release.draft.edges.drop(0).map { it.to }.drop(0).let { it.subList(0, 4) + listOf(release.draft.edges.last().to) })
        assertEquals("Who owns the fix?", editor.editable(account, DemoWorkflowIds.TRIAGE).draft.node("owner")?.question)
        assertEquals("", editor.editable(account, DemoWorkflowIds.RELEASE).draft.node("merge")?.question)
        assertFailsWith<AppException.NotFound> { editor.editable(account, "ghost") }
    }

    @Test fun aCreatedWorkflowIsAcceptedAtVersionOneAndReadBackAsItWasSent() = runTest {
        val draft = WorkflowDraft(name = " Mine ", domain = "eng", description = " ", nodes = listOf(DraftNode("t", NodeKind.TRIGGER, "Go"), DraftNode("s", NodeKind.STEP, "Do", instruction = "make")))
        val written = assertIs<WorkflowWrite.Applied<String>>(editor.create(account, draft))
        assertEquals("demo-workflow-new-1", written.value)
        val read = workflows.workflow(account, written.value)
        assertEquals("Mine", read.name)
        assertEquals("accepted", read.status)
        assertEquals(1, read.version)
        assertNull(read.description)
        assertEquals("make", read.nodes.last().detail)
        assertEquals(draft, editor.editable(account, written.value).draft)
        assertEquals(3, workflows.workflows(account).size)
        assertEquals("demo-workflow-new-2", (editor.create(account, draft) as WorkflowWrite.Applied).value)
    }

    @Test fun aSaveRaisesTheVersionAndKeepsTheStatusAndAStaleOneIsRefused() = runTest {
        val release = editor.editable(account, DemoWorkflowIds.RELEASE)
        assertEquals(WorkflowWrite.Applied(DemoWorkflowIds.RELEASE), editor.save(account, release, release.draft.copy(name = "Release v2")))
        assertEquals(4, editor.editable(account, DemoWorkflowIds.RELEASE).version)
        assertEquals("Release v2", workflows.workflow(account, DemoWorkflowIds.RELEASE).name)
        assertEquals("accepted", workflows.workflow(account, DemoWorkflowIds.RELEASE).status)
        assertEquals(listOf(DemoWorkflowIds.RELEASE, DemoWorkflowIds.TRIAGE), workflows.workflows(account).map { it.id }, "an edited built-in keeps its place")
        assertEquals(WorkflowEditRefusal.STALE, assertFailsWith<AppException.WorkflowEditRefused> { editor.save(account, release, release.draft) }.reason)
        val triage = editor.editable(account, DemoWorkflowIds.TRIAGE)
        editor.save(account, triage, triage.draft)
        assertEquals("proposed", workflows.workflow(account, DemoWorkflowIds.TRIAGE).status, "the status stays the console's decision")
        assertFailsWith<AppException.NotFound> { editor.save(account, release.copy(id = "ghost"), release.draft) }
    }

    @Test fun savingAWorkflowWithoutAVersionBeginsAtVersionOne() = runTest {
        val created = (editor.create(account, WorkflowDraft(name = "N")) as WorkflowWrite.Applied).value
        val read = editor.editable(account, created)
        editor.save(account, read.copy(version = read.version), read.draft)
        assertEquals(2, editor.editable(account, created).version)
        val noVersion = read.copy(id = DemoWorkflowIds.TRIAGE, version = 1)
        editor.save(account, noVersion, noVersion.draft)
        assertEquals(2, editor.editable(account, DemoWorkflowIds.TRIAGE).version)
    }

    @Test fun aDeletedWorkflowDisappearsFromEverywhere() = runTest {
        assertEquals(WorkflowWrite.Applied(Unit), editor.delete(account, DemoWorkflowIds.TRIAGE))
        assertEquals(listOf(DemoWorkflowIds.RELEASE), workflows.workflows(account).map { it.id })
        assertFailsWith<AppException.NotFound> { editor.editable(account, DemoWorkflowIds.TRIAGE) }
        assertFailsWith<AppException.NotFound> { editor.delete(account, DemoWorkflowIds.TRIAGE) }
    }

    @Test fun aCreatedWorkflowThatIsDeletedIsGoneToo() = runTest {
        val id = (editor.create(account, WorkflowDraft(name = "N")) as WorkflowWrite.Applied).value
        editor.delete(account, id)
        assertEquals(2, workflows.workflows(account).size)
    }
}
