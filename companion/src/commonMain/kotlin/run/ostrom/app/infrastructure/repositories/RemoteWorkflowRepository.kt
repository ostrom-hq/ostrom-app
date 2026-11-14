// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteWorkflowRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.RunVisit
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.RunStatus as ApiRunStatus
import run.ostrom.core.api.models.Workflow as ApiWorkflow
import run.ostrom.core.api.models.WorkflowNode as ApiWorkflowNode
import run.ostrom.core.api.models.WorkflowRun as ApiWorkflowRun

/** The workflows and their runs over the typed client (`/api/v1/workflows`, `/api/v1/runs`), read only. */
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
    start = start, inputs = inputs.map { WorkflowInput(it.id, it.label, it.required, it.default) },
    nodes = nodes.map { WorkflowNode(it.id, it.kind.domain(), it.label, it.detailLine()) }, updatedAt = millisOf(updatedAt ?: foundAt),
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
