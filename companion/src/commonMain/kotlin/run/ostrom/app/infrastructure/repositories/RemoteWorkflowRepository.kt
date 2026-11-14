// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteWorkflowRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.RunVisit
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.client.OstromException
import run.ostrom.core.api.models.RunStatus as ApiRunStatus
import run.ostrom.core.api.models.Workflow as ApiWorkflow
import run.ostrom.core.api.models.WorkflowNode as ApiWorkflowNode
import run.ostrom.core.api.models.WorkflowRun as ApiWorkflowRun

/** The workflows and their runs over the typed client (`/api/v1/workflows`, `/api/v1/runs`): read, started and cancelled. */
class RemoteWorkflowRepository(private val calls: AccountCalls) : WorkflowRepository {
    override suspend fun workflows(accountId: String): List<Workflow> = calls.withAccount(accountId) { client ->
        client.workflows.list().items.map { it.domain() }
    }

    override suspend fun workflow(accountId: String, workflowId: String): Workflow =
        calls.withAccount(accountId, resource = true) { it.workflows.get(workflowId).domain() }

    override suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?): WorkflowRunPage =
        calls.withAccount(accountId, resource = workflowId != null) { client ->
            val page = if (workflowId != null) client.workflows.runs(workflowId, status, before) else client.workflows.allRuns(status, before)
            WorkflowRunPage(page.items.map { it.domain() }, page.next)
        }

    override suspend fun run(accountId: String, runId: String): WorkflowRun =
        calls.withAccount(accountId, resource = true) { it.workflows.run(runId).domain() }

    override suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>): WorkflowRun =
        calls.withAccount(accountId, resource = true) { client -> refusals { client.workflows.start(workflowId, inputs).domain() } }

    override suspend fun cancel(accountId: String, runId: String): WorkflowRun =
        calls.withAccount(accountId, resource = true) { client -> refusals { client.workflows.cancel(runId).domain() } }

    /** The refusals of a start or a cancel, by their error key; the server's message is read only for its leading key, never shown. */
    private suspend fun <T> refusals(call: suspend () -> T): T = try {
        call()
    } catch (error: OstromException) {
        throw refusalOf(error)?.let { AppException.WorkflowRefused(it) } ?: error
    }

    private fun refusalOf(error: OstromException): WorkflowRefusal? {
        val key = error.message.orEmpty().substringBefore(':').trim()
        return when {
            error.status == 403 && error.type == "forbidden" -> WorkflowRefusal.FORBIDDEN
            error.status == 400 && error.type == "invalid_request" -> WorkflowRefusal.INVALID_INPUTS
            error.status == 409 && error.type == "run_limit" -> WorkflowRefusal.RUN_LIMIT
            error.status == 409 && key == "workflow_not_accepted" -> WorkflowRefusal.NOT_RUNNABLE
            error.status == 409 && (key == "run_ended" || error.type == "run_ended") -> WorkflowRefusal.RUN_ENDED
            error.status == 409 && key == "run_busy" -> WorkflowRefusal.RUN_BUSY
            else -> null
        }
    }
}

internal fun WorkflowNodeKind.domain(): NodeKind = when (this) {
    WorkflowNodeKind.TRIGGER -> NodeKind.TRIGGER
    WorkflowNodeKind.STEP -> NodeKind.STEP
    WorkflowNodeKind.CHECK -> NodeKind.CHECK
    WorkflowNodeKind.GATE -> NodeKind.GATE
    WorkflowNodeKind.NOTIFY -> NodeKind.NOTIFY
    WorkflowNodeKind.AGENT -> NodeKind.AGENT
    WorkflowNodeKind.WAIT -> NodeKind.WAIT
    WorkflowNodeKind.WORKFLOW -> NodeKind.WORKFLOW
    WorkflowNodeKind.UNKNOWN -> NodeKind.UNKNOWN
}

internal fun ApiRunStatus.domain(): RunStatus = RunStatus.entries.firstOrNull { it.wire == value && it != RunStatus.UNKNOWN } ?: RunStatus.UNKNOWN

/** What a node does, in one line: its command, its message, its instructions, its wait's title, the workflow it calls. */
private fun ApiWorkflowNode.detailLine(): String? =
    listOfNotNull(detail, run?.command, run?.instructions, run?.message, wait?.title, workflowId?.let { "→ $it" })
        .firstOrNull { it.isNotBlank() }?.lineSequence()?.first()?.trim()

internal fun ApiWorkflow.domain() = Workflow(
    id = id, name = name, domain = domain, description = description, version = version, repos = repos.orEmpty(), status = status.value,
    start = start, inputs = inputs.map { WorkflowInput(it.id, it.label, it.required, it.default, it.maxLength) },
    nodes = nodes.map { WorkflowNode(it.id, it.kind.domain(), it.label, it.detailLine(), it.workflowId?.takeIf { _ -> it.kind == WorkflowNodeKind.WORKFLOW }) }, updatedAt = millisOf(updatedAt ?: foundAt),
)

internal fun ApiWorkflowRun.domain() = WorkflowRun(
    id = id, workflowId = workflowId, workflowName = workflowName, workflowVersion = workflowVersion, status = status.domain(),
    startedBy = startedBy, source = source, inputs = inputs, currentNodeId = currentNodeId, currentNodeKind = currentNodeKind?.domain(),
    parentRunId = parentRunId, sessionId = sessionId, repository = repository,
    visits = visits.map {
        RunVisit(it.seq, it.nodeId, it.nodeTitle ?: it.nodeId, it.kind.domain(), it.status, it.outcome, it.output, it.sessionId, it.waitId, it.gateId,
            it.childRunId, millisOf(it.startedAt), millisOf(it.endedAt))
    },
    failure = failure, failureCode = failureCode, output = output, receiptId = receiptId, startedAt = millisOf(startedAt), endedAt = millisOf(endedAt),
)
