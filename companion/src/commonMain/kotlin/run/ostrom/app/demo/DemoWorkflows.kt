// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's workflows; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.RunVisit
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.services.Clock

private const val MINUTE = 60_000L

/** The demo's workflow and run ids. */
internal object DemoWorkflowIds {
    const val RELEASE = "demo-workflow-release"
    const val TRIAGE = "demo-workflow-triage"
    const val RUN_WAITING = "demo-run-waiting"
    const val RUN_DONE = "demo-run-done"
    const val RUN_CANCELLED = "demo-run-cancelled"
}

private val release = Workflow(
    DemoWorkflowIds.RELEASE, "Release the engine", "engineering", "Build, test, ask for the merge, then tag the version.", 3,
    listOf("analytical-engines/engine"), "accepted", "trigger",
    listOf(WorkflowInput("version", "Version", required = true, default = null), WorkflowInput("notes", "Release notes", required = false, default = "")),
    listOf(
        WorkflowNode("trigger", NodeKind.TRIGGER, "Started by hand", null),
        WorkflowNode("build", NodeKind.STEP, "Build and test", "./gradlew build"),
        WorkflowNode("agent", NodeKind.AGENT, "Write the changelog", "Summarize the merged pull requests since the last tag."),
        WorkflowNode("merge", NodeKind.GATE, "Approve the merge", null),
        WorkflowNode("tag", NodeKind.STEP, "Tag the version", "git tag v\${version}"),
    ),
    null,
)

private val triage = Workflow(
    DemoWorkflowIds.TRIAGE, "Triage a bug report", "support", "Reproduce, find the cause, ask who owns the fix.", 1, emptyList(), "proposed", "trigger",
    emptyList(),
    listOf(
        WorkflowNode("trigger", NodeKind.TRIGGER, "A new issue", null),
        WorkflowNode("reproduce", NodeKind.AGENT, "Reproduce the bug", "Write a failing test."),
        WorkflowNode("owner", NodeKind.WAIT, "Who owns the fix?", null),
    ),
    null,
)

/** The demo's built-in workflows, before the person's edits ([demoWorkflows]). */
internal fun builtInWorkflows(): List<Workflow> = listOf(release, triage)

/** The demo's runs, relative to [startedAt]: one waiting at its gate, one that succeeded, one cancelled at its gate. */
internal fun demoRuns(startedAt: Long): List<WorkflowRun> {
    val login = DemoContent.account().login
    fun visit(seq: Int, node: WorkflowNode, status: String, start: Int, end: Int?, outcome: String? = null, output: String? = null, sessionId: String? = null, gateId: String? = null) =
        RunVisit(seq, node.id, node.label, node.kind, status, outcome, output, sessionId, null, gateId, null, startedAt - start * MINUTE, end?.let { startedAt - it * MINUTE })
    val (trigger, build, agent, merge, tag) = release.nodes
    return listOf(
        WorkflowRun(
            DemoWorkflowIds.RUN_WAITING, release.id, release.name, 3, RunStatus.WAITING, login, "manual", mapOf("version" to "1.4.0"), merge.id, merge.kind,
            null, DemoSessionIds.OPEN, "analytical-engines/engine",
            listOf(
                visit(1, trigger, "done", 40, 40),
                visit(2, build, "done", 40, 31, outcome = "passed", output = "412 tests passed"),
                visit(3, agent, "done", 31, 12, sessionId = DemoSessionIds.OPEN, output = "Changelog drafted: 6 pull requests."),
                visit(4, merge, "active", 12, null, gateId = "demo-push"),
            ),
            null, null, null, null, startedAt - 40 * MINUTE, null,
        ),
        WorkflowRun(
            DemoWorkflowIds.RUN_DONE, release.id, release.name, 2, RunStatus.SUCCEEDED, login, "manual", mapOf("version" to "1.3.2"), null, null,
            null, DemoSessionIds.ENDED, "analytical-engines/engine",
            listOf(
                visit(1, trigger, "done", 26 * 60, 26 * 60),
                visit(2, build, "done", 26 * 60, 25 * 60 + 52, outcome = "passed"),
                visit(3, agent, "done", 25 * 60 + 52, 25 * 60 + 20, sessionId = DemoSessionIds.ENDED),
                visit(4, merge, "done", 25 * 60 + 20, 25 * 60 + 10, outcome = "approved"),
                visit(5, tag, "done", 25 * 60 + 10, 25 * 60 + 9, outcome = "passed", output = "v1.3.2"),
            ),
            null, null, "Tagged v1.3.2", null, startedAt - 26 * 60 * MINUTE, startedAt - (25 * 60 + 9) * MINUTE,
        ),
        WorkflowRun(
            DemoWorkflowIds.RUN_CANCELLED, release.id, release.name, 2, RunStatus.CANCELLED, login, "manual", mapOf("version" to "1.3.1"), null, null,
            null, null, "analytical-engines/engine",
            listOf(
                visit(1, trigger, "done", 50 * 60, 50 * 60),
                visit(2, build, "done", 50 * 60, 49 * 60 + 50, outcome = "passed"),
                // As the server cancels: the visit under way fails with the outcome `cancelled`.
                visit(3, merge, "failed", 49 * 60 + 50, 49 * 60, outcome = "cancelled"),
            ),
            null, null, null, null, startedAt - 50 * 60 * MINUTE, startedAt - 49 * 60 * MINUTE,
        ),
    )
}

/**
 * The demo's workflows and runs from memory, in one page. A run started here moves on its first node after the trigger; a
 * cancelled run ends as cancelled. Nothing leaves the device.
 */
class DemoWorkflowRepository(private val demo: DemoMode, private val clock: Clock) : WorkflowRepository {
    /** The built-in runs, then those the person started, each as the person last left it. */
    private fun all(store: DemoStore): List<WorkflowRun> {
        val cancelled = store.cancelledRuns.value
        return (store.startedRuns.value + demoRuns(store.startedAt)).map { run ->
            // As the server cancels: the visit under way fails with the outcome `cancelled`.
            cancelled[run.id]?.let { at -> run.copy(status = RunStatus.CANCELLED, currentNodeId = null, currentNodeKind = null, endedAt = at, visits = run.visits.map { if (it.active) it.copy(status = "failed", outcome = "cancelled", endedAt = at) else it }) } ?: run
        }
    }

    override suspend fun workflows(accountId: String): List<Workflow> = demoWorkflows(demo.store())

    override suspend fun workflow(accountId: String, workflowId: String): Workflow =
        demoWorkflows(demo.store()).firstOrNull { it.id == workflowId } ?: throw AppException.NotFound()

    override suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?): WorkflowRunPage {
        val runs = all(demo.store()).filter { (workflowId == null || it.workflowId == workflowId) && (status == null || it.status.wire == status) }
        return WorkflowRunPage(if (before == null) runs else emptyList(), null)
    }

    override suspend fun run(accountId: String, runId: String): WorkflowRun =
        all(demo.store()).firstOrNull { it.id == runId } ?: throw AppException.NotFound()

    override suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>): WorkflowRun {
        val store = demo.store()
        val workflow = workflow(accountId, workflowId)
        if (!workflow.runnable) throw AppException.WorkflowRefused(WorkflowRefusal.NOT_RUNNABLE)
        val now = clock.nowMillis()
        // The trigger passes at once; the run moves on its first real node.
        val first = workflow.nodes.firstOrNull { it.kind != NodeKind.TRIGGER } ?: workflow.nodes.first()
        val run = WorkflowRun(
            "demo-run-started-${store.startedRuns.value.size + 1}", workflow.id, workflow.name, workflow.version ?: 1, RunStatus.RUNNING, store.account.value.login,
            "manual", inputs, first.id, first.kind, null, null, workflow.repos.firstOrNull(),
            listOf(RunVisit(1, first.id, first.label, first.kind, "active", null, null, null, null, null, null, now, null)),
            null, null, null, null, now, null,
        )
        store.startedRuns.value = listOf(run) + store.startedRuns.value
        return run
    }

    override suspend fun cancel(accountId: String, runId: String): WorkflowRun {
        val store = demo.store()
        val run = run(accountId, runId)
        if (!run.status.active) throw AppException.WorkflowRefused(WorkflowRefusal.RUN_ENDED)
        store.cancelledRuns.value = store.cancelledRuns.value + (runId to clock.nowMillis())
        return run(accountId, runId).copy(visits = emptyList())
    }
}

/** The demo account's workflows from memory, every other account's from its server. */
class DemoAwareWorkflowRepository(private val real: WorkflowRepository, private val demo: WorkflowRepository) : WorkflowRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun workflows(accountId: String) = of(accountId).workflows(accountId)
    override suspend fun workflow(accountId: String, workflowId: String) = of(accountId).workflow(accountId, workflowId)
    override suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?) = of(accountId).runs(accountId, workflowId, status, before)
    override suspend fun run(accountId: String, runId: String) = of(accountId).run(accountId, runId)
    override suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>) = of(accountId).start(accountId, workflowId, inputs)
    override suspend fun cancel(accountId: String, runId: String) = of(accountId).cancel(accountId, runId)
}
