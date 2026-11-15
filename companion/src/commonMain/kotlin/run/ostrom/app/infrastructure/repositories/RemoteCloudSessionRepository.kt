// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteSessionRepository.kt and CloudMapping.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudFailure
import run.ostrom.app.domain.models.CloudLimits
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.SessionWaiting
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.core.api.models.AnswerCloudPermissionRequest
import run.ostrom.core.api.models.CloudAgentKind
import run.ostrom.core.api.models.CloudInput
import run.ostrom.core.api.models.CloudQuestionAnswer
import run.ostrom.core.api.models.CloudEnvironment
import run.ostrom.core.api.models.CloudSetup
import run.ostrom.core.api.models.CreateCloudSessionRequest
import run.ostrom.core.api.models.SendCloudMessageRequest
import run.ostrom.core.client.OstromException
import run.ostrom.core.api.models.CloudRepository as ApiCloudRepository
import run.ostrom.core.api.models.CloudSession as ApiCloudSession

/** The person's cloud sessions over the typed client (`/api/v1/cloud/sessions`): read, started, paused, resumed, stopped. */
class RemoteCloudSessionRepository(private val calls: AccountCalls) : CloudSessionRepository {
    override suspend fun sessions(accountId: String, before: String?): CloudSessionPage = calls.withAccount(accountId) { client ->
        val page = client.cloudSessions.list(before = before, limit = PAGE)
        CloudSessionPage(page.items.map { it.domain() }, page.next)
    }

    override suspend fun session(accountId: String, sessionId: String): CloudSession =
        calls.withAccount(accountId, resource = true) { it.cloudSessions.get(sessionId).domain() }

    override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String): CloudSession =
        calls.withAccount(accountId) { client ->
            refusals(creating = true) { client.cloudSessions.create(session.request(), idempotencyKey).domain() }
        }

    override suspend fun pause(accountId: String, sessionId: String): CloudSession =
        calls.withAccount(accountId, resource = true) { client -> refusals { client.cloudSessions.pause(sessionId).domain() } }

    override suspend fun resume(accountId: String, sessionId: String): CloudSession =
        calls.withAccount(accountId, resource = true) { client -> refusals { client.cloudSessions.resume(sessionId).domain() } }

    override suspend fun stop(accountId: String, sessionId: String): CloudSession =
        calls.withAccount(accountId, resource = true) { client -> refusals { client.cloudSessions.stop(sessionId).domain() } }

    override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession =
        calls.withAccount(accountId, resource = true) { client -> refusals(githubAccess = true) { client.cloudSessions.setGitHubAccess(sessionId, access.wire).domain() } }

    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String>): InputReceipt =
        calls.withAccount(accountId, resource = true) { it.cloudMessages.send(sessionId, SendCloudMessageRequest(text, wake = true, fileIds = fileIds), idempotencyKey = clientMessageId).receipt() }

    override suspend fun interrupt(accountId: String, sessionId: String): InputReceipt =
        calls.withAccount(accountId, resource = true) { it.cloudMessages.interrupt(sessionId).receipt() }

    override suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt =
        calls.withAccount(accountId, resource = true) { client ->
            val request = AnswerCloudPermissionRequest(
                requestId = answer.requestId,
                behavior = if (answer.allow) "allow" else "deny",
                message = answer.message,
                answers = answer.answers.takeIf { it.isNotEmpty() }?.map { CloudQuestionAnswer(it.question, it.options, it.text) },
            )
            client.cloudMessages.answerPermission(sessionId, request, idempotencyKey).receipt()
        }

    override suspend fun stopTask(accountId: String, sessionId: String, taskId: String): InputReceipt =
        calls.withAccount(accountId, resource = true) { it.cloudMessages.stopTask(sessionId, taskId).receipt() }

    override suspend fun setPermissionBypass(accountId: String, sessionId: String, enabled: Boolean): CloudSession =
        calls.withAccount(accountId, resource = true) { it.cloudSessions.setPermissionBypass(sessionId, enabled).domain() }

    override suspend fun setModel(accountId: String, sessionId: String, model: String): CloudSession =
        calls.withAccount(accountId, resource = true) { it.cloudSessions.setModel(sessionId, model).domain() }

    override suspend fun setEffort(accountId: String, sessionId: String, effort: String): CloudSession =
        calls.withAccount(accountId, resource = true) { it.cloudSessions.setEffort(sessionId, effort).domain() }

    /** The refusals of an action, by their error key; the server's message is never read nor shown. */
    private suspend fun <T> refusals(creating: Boolean = false, githubAccess: Boolean = false, call: suspend () -> T): T = try {
        call()
    } catch (error: OstromException) {
        throw cloudRefusalOf(error.status, error.type, creating, githubAccess)?.let { AppException.CloudSessionRefused(it) } ?: error
    }

    private companion object { const val PAGE = 50 }
}

/**
 * The refusal a status and error key stand for; null for any other failure (translated as usual). Only a start
 * ([creating]) has values to check: an action's `invalid_request` stays a plain server error.
 */
internal fun cloudRefusalOf(status: Int, type: String?, creating: Boolean, githubAccess: Boolean = false): CloudSessionRefusal? = when (type) {
    "invalid_request" -> CloudSessionRefusal.INVALID.takeIf { creating }
    "payment_required" -> CloudSessionRefusal.PLAN_LIMIT
    "conflict" -> CloudSessionRefusal.STATE
    "forbidden" -> CloudSessionRefusal.FORBIDDEN
    "profile_required" -> CloudSessionRefusal.PROFILE_REQUIRED
    "cloud_profile_not_found", "profile_unassigned", "image_not_allowed" -> CloudSessionRefusal.PROFILE_UNAVAILABLE
    "cluster_unavailable", "namespace_not_allowed" -> CloudSessionRefusal.CLUSTER_UNAVAILABLE
    "github_owner_only" -> CloudSessionRefusal.GITHUB_OWNER_ONLY.takeIf { githubAccess }
    "session_not_paused" -> CloudSessionRefusal.GITHUB_NOT_PAUSED.takeIf { githubAccess }
    else -> null
}.takeIf { status in 400..499 }

internal fun ApiCloudSession.domain() = CloudSession(
    id = id,
    name = name,
    agent = agent,
    model = model,
    repo = repo,
    ref = ref,
    status = CloudSessionStatus.of(status),
    desiredStatus = desiredStatus,
    phase = phase,
    failure = failure?.let { CloudFailure(it.code, it.message, it.retryable) },
    limits = limits?.let { CloudLimits(it.idleTimeoutMinutes, it.maxRunMinutes, it.restrictedEgress) },
    createdAt = millisOf(createdAt),
    updatedAt = millisOf(updatedAt),
    readyAt = millisOf(readyAt),
    lastActivityAt = millisOf(lastActivityAt),
    pausedAt = millisOf(pausedAt),
    endedAt = millisOf(endedAt),
    recipe = environment?.let { recipe ->
        CloudRecipe(
            repositories = recipe.repositories.map { CloudRepository(it.url, it.path, it.ref, it.branch) },
            workingDirectory = recipe.workingDirectory,
            setupCreate = recipe.setup?.create.orEmpty(),
            setupStart = recipe.setup?.start.orEmpty(),
            requiredVariables = recipe.requiredVariables,
            requireGitHub = recipe.requireGitHub == true,
            gitEmailDomain = recipe.gitEmailDomain,
        )
    },
    githubAccess = GitHubAccess.of(githubAccess),
    ownerId = owner,
    transferId = transferId,
    remote = kind == "remote",
    mode = if (mode == CONVERSATION_MODE) CloudSessionMode.CONVERSATION else CloudSessionMode.TERMINAL,
    effort = effort,
    permissionBypass = permissionBypass == true,
    waiting = waiting?.let { SessionWaiting(it.reason, it.requestId, millisOf(it.since)) },
)

private const val CONVERSATION_MODE = "conversation"

internal fun CloudInput.receipt() = InputReceipt(id, status, woke, reason)

internal fun NewCloudSession.request() = CreateCloudSessionRequest(
    agent = CloudAgentKind.entries.first { it.value == agent.wire },
    model = model,
    repo = repo,
    ref = ref,
    prompt = prompt,
    name = name,
    mode = if (mode == CloudSessionMode.CONVERSATION) CONVERSATION_MODE else null,
    environment = recipe?.let { recipe ->
        CloudEnvironment(
            repositories = recipe.repositories.map { ApiCloudRepository(it.url, it.path, it.ref, it.branch) },
            workingDirectory = recipe.workingDirectory,
            setup = CloudSetup(recipe.setupCreate, recipe.setupStart).takeIf { it.create.isNotEmpty() || it.start.isNotEmpty() },
            requiredVariables = recipe.requiredVariables,
            requireGitHub = recipe.requireGitHub.takeIf { it },
            gitEmailDomain = recipe.gitEmailDomain,
        )
    },
)
