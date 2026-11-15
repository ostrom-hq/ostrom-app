// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's cloud sessions; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudFailure
import run.ostrom.app.domain.models.CloudLimits
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.services.Clock

private const val MINUTE = 60_000L

/** The demo's cloud session ids. */
internal object DemoCloudSessionIds {
    const val RUNNING = "demo-cloud-mill"
    const val PREPARING = "demo-cloud-setup"
    const val RETRYABLE = "demo-cloud-docs"
    const val BROKEN = "demo-cloud-cards"
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

    override suspend fun create(accountId: String, session: NewCloudSession): CloudSession {
        val store = demo.store()
        val now = clock.nowMillis()
        val name = session.name ?: (session.recipe?.repositories?.firstOrNull()?.url ?: session.repo)?.substringAfterLast('/')?.removeSuffix(".git") ?: session.agent.wire
        val created = CloudSession(
            "demo-cloud-new-${store.cloudSessions.value.size + 1}", name, session.agent.wire, session.model, session.repo, session.ref, CloudSessionStatus.RUNNING,
            "running", null, null, demoLimits, now, now, now, now, null, null, session.recipe,
            GitHubAccess.OWNER,
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
    override suspend fun create(accountId: String, session: NewCloudSession) = of(accountId).create(accountId, session)
    override suspend fun pause(accountId: String, sessionId: String) = of(accountId).pause(accountId, sessionId)
    override suspend fun resume(accountId: String, sessionId: String) = of(accountId).resume(accountId, sessionId)
    override suspend fun stop(accountId: String, sessionId: String) = of(accountId).stop(accountId, sessionId)
    override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess) = of(accountId).setGitHubAccess(accountId, sessionId, access)
}
