// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's cloud sessions; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudFailure
import run.ostrom.app.domain.models.CloudLimits
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.SessionTransfer
import run.ostrom.app.domain.models.TeamMember
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferPage
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.repositories.TransferRepository
import run.ostrom.app.domain.services.Clock

private const val MINUTE = 60_000L
private const val HTTP_CONFLICT = 409

/** The demo's cloud session ids. */
internal object DemoCloudSessionIds {
    const val RUNNING = "demo-cloud-mill"
    const val PREPARING = "demo-cloud-setup"
    const val RETRYABLE = "demo-cloud-docs"
    const val BROKEN = "demo-cloud-cards"
    const val CONVERSATION = "demo-cloud-talk"
}

private val demoLimits = CloudLimits(idleTimeoutMinutes = 30, maxRunMinutes = 240, restrictedEgress = true)

private fun demoCloudSession(
    id: String, name: String, status: CloudSessionStatus, desired: String, created: Long, phase: String? = null, failure: CloudFailure? = null,
    recipe: CloudRecipe? = null, repo: String? = null, ref: String? = null, readyAt: Long? = null, pausedAt: Long? = null, endedAt: Long? = null,
) = CloudSession(
    id, name, "claude", "claude-opus", repo, ref, status, desired, phase, failure, demoLimits, created, created, readyAt, readyAt, pausedAt, endedAt, recipe,
    GitHubAccess.OWNER,
)

/**
 * The demo's cloud sessions, relative to [startedAt]: one running on a two-repository recipe, one running its setup,
 * one paused by a preparation that may pass on a resume, and one whose recipe must be fixed.
 */
internal fun demoCloudSessions(startedAt: Long): List<CloudSession> {
    val recipe = CloudRecipe(
        repositories = listOf(
            CloudRepository("https://github.com/analytical-engines/engine", "engine", "main", "carry-tens"),
            CloudRepository("https://github.com/analytical-engines/cards", "cards"),
        ),
        workingDirectory = "engine",
        setupCreate = listOf("./gradlew dependencies"),
        setupStart = listOf("git -C ../cards pull --ff-only"),
        requiredVariables = listOf("MILL_TOKEN"),
        requireGitHub = true,
        gitEmailDomain = "analytical-engines.example",
    )
    return listOf(
        demoCloudSession(DemoCloudSessionIds.CONVERSATION, "Bernoulli numbers", CloudSessionStatus.RUNNING, "running", startedAt - 20 * MINUTE,
            repo = "https://github.com/analytical-engines/engine", ref = "main", readyAt = startedAt - 19 * MINUTE)
            .copy(mode = CloudSessionMode.CONVERSATION, ownerId = DemoContent.MEMBER_ID, effort = "high"),
        demoCloudSession(DemoCloudSessionIds.RUNNING, "Carry the tens", CloudSessionStatus.RUNNING, "running", startedAt - 50 * MINUTE, recipe = recipe,
            readyAt = startedAt - 47 * MINUTE),
        demoCloudSession(DemoCloudSessionIds.PREPARING, "Punch the cards", CloudSessionStatus.CREATING, "running", startedAt - 2 * MINUTE, phase = "running_setup",
            recipe = recipe),
        demoCloudSession(DemoCloudSessionIds.RETRYABLE, "Engine notes", CloudSessionStatus.PAUSED, "paused", startedAt - 3 * 60 * MINUTE,
            failure = CloudFailure(CloudFailure.PREPARATION_FAILED, "Cloning repository 1 failed (exit code 128).", retryable = true),
            repo = "https://github.com/analytical-engines/notes", ref = "main", pausedAt = startedAt - 3 * 60 * MINUTE + 2 * MINUTE),
        demoCloudSession(DemoCloudSessionIds.BROKEN, "Difference tables", CloudSessionStatus.FAILED, "running", startedAt - 26 * 60 * MINUTE,
            failure = CloudFailure(CloudFailure.PREPARATION_FAILED, "Setup command 1 (create) failed (exit code 1).", retryable = false),
            recipe = recipe.copy(setupCreate = listOf("make tables")), endedAt = startedAt - 26 * 60 * MINUTE + 4 * MINUTE),
    )
}

/** The demo's cloud sessions from memory: started, paused, resumed and stopped at once, in one page. */
class DemoCloudSessionRepository(private val demo: DemoMode, private val clock: Clock) : CloudSessionRepository {
    override suspend fun sessions(accountId: String, before: String?): CloudSessionPage =
        CloudSessionPage(if (before == null) demo.store().cloudSessions.value else emptyList(), null)

    override suspend fun session(accountId: String, sessionId: String): CloudSession =
        demo.store().cloudSessions.value.firstOrNull { it.id == sessionId } ?: throw AppException.NotFound()

    override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String): CloudSession {
        val store = demo.store()
        val now = clock.nowMillis()
        val name = session.name ?: (session.recipe?.repositories?.firstOrNull()?.url ?: session.repo)?.substringAfterLast('/')?.removeSuffix(".git") ?: session.agent.wire
        val created = CloudSession(
            "demo-cloud-new-${store.cloudSessions.value.size + 1}", name, session.agent.wire, session.model, session.repo, session.ref, CloudSessionStatus.RUNNING,
            "running", null, null, demoLimits, now, now, now, now, null, null, session.recipe,
            GitHubAccess.OWNER, ownerId = DemoContent.MEMBER_ID, mode = session.mode,
        )
        store.cloudSessions.value = listOf(created) + store.cloudSessions.value
        return created
    }

    override suspend fun pause(accountId: String, sessionId: String): CloudSession =
        change(sessionId) { it.copy(status = CloudSessionStatus.PAUSED, desiredStatus = CloudSession.PAUSED, phase = null, pausedAt = clock.nowMillis()) }

    override suspend fun resume(accountId: String, sessionId: String): CloudSession {
        val now = clock.nowMillis()
        return change(sessionId) { it.copy(status = CloudSessionStatus.RUNNING, desiredStatus = "running", failure = null, pausedAt = null, readyAt = now, lastActivityAt = now) }
    }

    override suspend fun stop(accountId: String, sessionId: String): CloudSession =
        change(sessionId) { it.copy(status = CloudSessionStatus.TERMINATED, desiredStatus = CloudSession.TERMINATED, phase = null, endedAt = clock.nowMillis()) }

    override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession {
        // As the server: taking the access away needs the session paused.
        if (access == GitHubAccess.NONE && demo.store().cloudSessions.value.firstOrNull { it.id == sessionId }?.status != CloudSessionStatus.PAUSED) {
            throw AppException.CloudSessionRefused(CloudSessionRefusal.GITHUB_NOT_PAUSED)
        }
        return change(sessionId) { it.copy(githubAccess = access) }
    }

    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String): InputReceipt {
        val session = session(accountId, sessionId)
        if (session.status.ended) throw AppException.Server(HTTP_CONFLICT, "session_not_running")
        // A message wakes a paused session, as on a server.
        val woke = session.status == CloudSessionStatus.PAUSED
        if (woke) resume(accountId, sessionId)
        return InputReceipt(demo.store().exchange(sessionId, text, clock.nowMillis()), "consumed", woke, null)
    }

    override suspend fun interrupt(accountId: String, sessionId: String): InputReceipt =
        InputReceipt(demo.store().interrupted(sessionId, clock.nowMillis()), "consumed", false, null)

    override suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt =
        InputReceipt(demo.store().answered(sessionId, answer, clock.nowMillis()), "consumed", false, null)

    override suspend fun stopTask(accountId: String, sessionId: String, taskId: String): InputReceipt =
        InputReceipt(demo.store().stoppedTask(sessionId, taskId, clock.nowMillis()), "consumed", false, null)

    override suspend fun setPermissionBypass(accountId: String, sessionId: String, enabled: Boolean): CloudSession =
        change(sessionId) { it.copy(permissionBypass = enabled) }

    override suspend fun setModel(accountId: String, sessionId: String, model: String): CloudSession = change(sessionId) { it.copy(model = model) }

    override suspend fun setEffort(accountId: String, sessionId: String, effort: String): CloudSession = change(sessionId) { it.copy(effort = effort) }

    private fun change(sessionId: String, update: (CloudSession) -> CloudSession): CloudSession {
        val store = demo.store()
        val current = store.cloudSessions.value.firstOrNull { it.id == sessionId } ?: throw AppException.NotFound()
        if (current.status.ended) throw AppException.CloudSessionRefused(CloudSessionRefusal.STATE)
        val changed = update(current).copy(updatedAt = clock.nowMillis())
        store.cloudSessions.value = store.cloudSessions.value.map { if (it.id == sessionId) changed else it }
        return changed
    }
}

/** The demo account's cloud sessions from memory, every other account's from its server. */
class DemoAwareCloudSessionRepository(private val real: CloudSessionRepository, private val demo: CloudSessionRepository) : CloudSessionRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun sessions(accountId: String, before: String?) = of(accountId).sessions(accountId, before)
    override suspend fun session(accountId: String, sessionId: String) = of(accountId).session(accountId, sessionId)
    override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String) =
        of(accountId).create(accountId, session, idempotencyKey)
    override suspend fun pause(accountId: String, sessionId: String) = of(accountId).pause(accountId, sessionId)
    override suspend fun resume(accountId: String, sessionId: String) = of(accountId).resume(accountId, sessionId)
    override suspend fun stop(accountId: String, sessionId: String) = of(accountId).stop(accountId, sessionId)
    override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess) = of(accountId).setGitHubAccess(accountId, sessionId, access)
    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String) = of(accountId).send(accountId, sessionId, text, clientMessageId)
    override suspend fun interrupt(accountId: String, sessionId: String) = of(accountId).interrupt(accountId, sessionId)
    override suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String) =
        of(accountId).answer(accountId, sessionId, answer, idempotencyKey)
    override suspend fun stopTask(accountId: String, sessionId: String, taskId: String) = of(accountId).stopTask(accountId, sessionId, taskId)
    override suspend fun setPermissionBypass(accountId: String, sessionId: String, enabled: Boolean) = of(accountId).setPermissionBypass(accountId, sessionId, enabled)
    override suspend fun setModel(accountId: String, sessionId: String, model: String) = of(accountId).setModel(accountId, sessionId, model)
    override suspend fun setEffort(accountId: String, sessionId: String, effort: String) = of(accountId).setEffort(accountId, sessionId, effort)
}

/** The demo has no team to transfer sessions to (it announces no transfers), so its screens stay quiet. */
object DemoTransferRepository : TransferRepository {
    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer =
        throw AppException.Unsupported(Features.RUNNERS_CLOUD_TRANSFER)
    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?) = TransferPage(emptyList(), null)
    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?): SessionTransfer = throw AppException.NotFound()
    override suspend fun decline(accountId: String, transferId: String): SessionTransfer = throw AppException.NotFound()
    override suspend fun cancel(accountId: String, transferId: String): SessionTransfer = throw AppException.NotFound()
    override suspend fun recipients(accountId: String): List<TeamMember> = emptyList()
}

/** The demo account's transfers (none) from memory, every other account's from its server. */
class DemoAwareTransferRepository(private val real: TransferRepository, private val demo: TransferRepository) : TransferRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String) =
        of(accountId).transfer(accountId, sessionId, request, idempotencyKey)
    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?) = of(accountId).transfers(accountId, direction, before)
    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?) = of(accountId).accept(accountId, transferId, resume)
    override suspend fun decline(accountId: String, transferId: String) = of(accountId).decline(accountId, transferId)
    override suspend fun cancel(accountId: String, transferId: String) = of(accountId).cancel(accountId, transferId)
    override suspend fun recipients(accountId: String) = of(accountId).recipients(accountId)
}
