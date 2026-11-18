// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/sessions/SessionActionUseCases.kt and SessionUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.sessions

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.SessionKinds
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** The cloud sessions behind the account's guard, refused as [AppException.Unsupported] by a server without them. */
class CloudSessionAccess(val repository: CloudSessionRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend fun <T> call(accountId: String, block: suspend (announced: Set<String>) -> T): T = guard(accountId) {
        val announced = features.capabilities(accountId).features
        if (Features.RUNNERS_CLOUD !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD)
        block(announced)
    }
}

/**
 * One page of the person's sessions, newest first, of every kind: a server that announces remote sessions is asked for
 * the kinds it knows ([SessionKinds.announced]), an older one is asked as before and answers its cloud sessions.
 */
class LoadCloudSessionsUseCase(private val access: CloudSessionAccess) {
    suspend operator fun invoke(accountId: String, before: String? = null): CloudSessionPage =
        access.call(accountId) { announced ->
            when {
                Features.RUNNERS_CLOUD_REMOTE in announced ->
                    access.repository.sessions(accountId, before, SessionKinds.announced(announced)).copy(remoteSessions = true)
                else -> access.repository.sessions(accountId, before)
            }
        }
}

/** One cloud session's record. */
class GetCloudSessionUseCase(private val access: CloudSessionAccess) {
    suspend operator fun invoke(accountId: String, sessionId: String): CloudSession {
        if (sessionId.isBlank()) throw AppException.NotFound()
        return access.call(accountId) { access.repository.session(accountId, sessionId) }
    }
}

/**
 * Pause, resume and stop, each offered only in the states the server accepts it in ([CloudSession.canPause],
 * [CloudSession.canResume], [CloudSession.canStop]): an action asked in another state is refused before any request.
 */
class ControlCloudSessionUseCase(private val access: CloudSessionAccess) {
    suspend fun pause(accountId: String, session: CloudSession): CloudSession {
        if (!session.canPause) throw AppException.CloudSessionRefused(CloudSessionRefusal.STATE)
        return access.call(accountId) { access.repository.pause(accountId, session.id) }
    }

    suspend fun resume(accountId: String, session: CloudSession): CloudSession {
        if (!session.canResume) throw AppException.CloudSessionRefused(CloudSessionRefusal.STATE)
        return access.call(accountId) { access.repository.resume(accountId, session.id) }
    }

    suspend fun stop(accountId: String, session: CloudSession): CloudSession {
        if (!session.canStop) throw AppException.CloudSessionRefused(CloudSessionRefusal.STATE)
        return access.call(accountId) { access.repository.stop(accountId, session.id) }
    }
}

/**
 * What the screens of a cloud session offer: the preparation recipe when the server takes one ([recipe]), the choice
 * of the session's GitHub access when it announces `runners.cloud.github.v1` ([githubAccess]), and sessions started on
 * the member's online devices ([onDevices], [resumes]).
 */
data class CloudSessionOptions(
    val recipe: Boolean,
    val githubAccess: Boolean = false,
    val conversation: Boolean = false,
    /** The server starts sessions on the member's online devices (`runners.cloud.remote.daemon.v1`). */
    val onDevices: Boolean = false,
    /** ... and lets such a session continue a conversation of the folder (`runners.cloud.remote.resume.v1`). */
    val resumes: Boolean = false,
)

class LoadCloudSessionOptionsUseCase(private val access: CloudSessionAccess) {
    suspend operator fun invoke(accountId: String): CloudSessionOptions =
        access.call(accountId) { announced ->
            CloudSessionOptions(
                recipe = Features.RUNNERS_CLOUD_ENVIRONMENT in announced,
                githubAccess = Features.RUNNERS_CLOUD_GITHUB in announced,
                conversation = Features.RUNNERS_CLOUD_CONVERSATION in announced,
                onDevices = Features.RUNNERS_CLOUD_REMOTE_DAEMON in announced,
                resumes = Features.RUNNERS_CLOUD_REMOTE_DAEMON in announced && Features.RUNNERS_CLOUD_REMOTE_RESUME in announced,
            )
        }
}

/**
 * Chooses whose GitHub access a cloud session uses, on a server with `runners.cloud.github.v1` (the sessions listed are
 * the person's own: the owner's). A session that ended is refused before any request; taking the access away from a
 * session that is not paused is the server's refusal (`session_not_paused`), worded by its key.
 */
class SetCloudSessionGitHubAccessUseCase(private val access: CloudSessionAccess) {
    suspend operator fun invoke(accountId: String, session: CloudSession, choice: GitHubAccess): CloudSession = access.call(accountId) { announced ->
        if (Features.RUNNERS_CLOUD_GITHUB !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_GITHUB)
        if (session.status.ended) throw AppException.CloudSessionRefused(CloudSessionRefusal.STATE)
        access.repository.setGitHubAccess(accountId, session.id, choice)
    }
}

/**
 * Starts a cloud session with what the form holds, checked first ([checkNewCloudSession]). A server without
 * `runners.cloud.environment.v1` would ignore a recipe: there, only the single repository goes. The caller keeps
 * [idempotencyKey] while the form is unchanged, so pressing Start again after a lost answer returns the first session.
 */
class CreateCloudSessionUseCase(private val access: CloudSessionAccess) {
    suspend operator fun invoke(accountId: String, form: NewCloudSession, idempotencyKey: String): CloudSession =
        access.call(accountId) { announced ->
            val sent = checkNewCloudSession(
                form,
                recipeServed = Features.RUNNERS_CLOUD_ENVIRONMENT in announced,
                conversationServed = Features.RUNNERS_CLOUD_CONVERSATION in announced,
            )
            access.repository.create(accountId, sent, idempotencyKey)
        }
}

/** The server's rules a form is checked against (TeamCode's and the server's `CloudEnvironmentRules`). */
object CloudSessionRules {
    const val MAX_NAME = 120
    const val MAX_PROMPT = 20_000
    const val MAX_REPOSITORIES = 16
    const val MAX_COMMANDS = 16
    const val MAX_COMMAND_LENGTH = 4096
    const val MAX_VARIABLES = 64

    /** The agents a conversation session runs headless (`400 invalid_mode` for the others). */
    val CONVERSATION_AGENTS = setOf(CloudAgent.CLAUDE, CloudAgent.CODEX)
    private val GITHUB = Regex("https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}")
    private val VARIABLE = Regex("[A-Z_][A-Z0-9_]{0,127}")
    private val DOMAIN = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")

    fun githubUrl(url: String): Boolean = GITHUB.matches(url.removeSuffix(".git"))
    fun variable(name: String): Boolean = VARIABLE.matches(name)
    fun domain(name: String): Boolean = DOMAIN.matches(name)
}

/**
 * Checks and tidies a form before anything is sent (the server checks again and its refusals are worded by key): text
 * trimmed, empty optional fields left out, empty rows dropped; the recipe when [recipeServed] and it holds anything,
 * otherwise the single repository and ref.
 */
fun checkNewCloudSession(form: NewCloudSession, recipeServed: Boolean, conversationServed: Boolean = false): NewCloudSession {
    val name = form.name.orNull()
    if (name != null && (name.length > CloudSessionRules.MAX_NAME || name.any(Char::isISOControl))) throw AppException.CloudSessionInvalid(CloudSessionProblem.NAME)
    val prompt = form.prompt?.takeIf { it.isNotBlank() }
    if (prompt != null && prompt.length > CloudSessionRules.MAX_PROMPT) throw AppException.CloudSessionInvalid(CloudSessionProblem.PROMPT)
    // A conversation only where the server runs one, and only for an agent it runs headless; a terminal otherwise.
    val mode = if (conversationServed && form.agent in CloudSessionRules.CONVERSATION_AGENTS) form.mode else CloudSessionMode.TERMINAL
    val base = NewCloudSession(form.agent, name = name, model = form.model.orNull(), prompt = prompt, mode = mode)
    val recipe = form.recipe?.takeIf { recipeServed }?.let { given -> given.tidy().takeIf { !it.empty } }
    if (recipe != null) {
        val commands = recipe.setupCreate + recipe.setupStart
        if (recipe.repositories.size > CloudSessionRules.MAX_REPOSITORIES || recipe.setupCreate.size > CloudSessionRules.MAX_COMMANDS ||
            recipe.setupStart.size > CloudSessionRules.MAX_COMMANDS || commands.any { it.length > CloudSessionRules.MAX_COMMAND_LENGTH }
        ) throw AppException.CloudSessionInvalid(CloudSessionProblem.TOO_MANY)
        // A row with a directory, a ref or a branch but no address is a mistake, never dropped silently.
        if (recipe.repositories.any { !CloudSessionRules.githubUrl(it.url) }) throw AppException.CloudSessionInvalid(CloudSessionProblem.REPOSITORY)
        val variables = recipe.requiredVariables
        if (variables.size > CloudSessionRules.MAX_VARIABLES || variables.any { !CloudSessionRules.variable(it) } || variables.toSet().size != variables.size) {
            throw AppException.CloudSessionInvalid(CloudSessionProblem.VARIABLES)
        }
        if (recipe.gitEmailDomain?.let(CloudSessionRules::domain) == false) throw AppException.CloudSessionInvalid(CloudSessionProblem.EMAIL_DOMAIN)
        return base.copy(recipe = recipe)
    }
    val repo = form.repo.orNull() ?: return base
    if (!CloudSessionRules.githubUrl(repo)) throw AppException.CloudSessionInvalid(CloudSessionProblem.REPOSITORY)
    return base.copy(repo = repo, ref = form.ref.orNull())
}

private fun String?.orNull(): String? = orEmpty().trim().ifEmpty { null }

private fun CloudRecipe.tidy() = CloudRecipe(
    // Only a row left wholly empty is dropped; one with a directory, ref or branch but no address is kept, and refused.
    repositories = repositories.map { CloudRepository(it.url.trim(), it.path.orNull(), it.ref.orNull(), it.branch.orNull()) }
        .filterNot { it.url.isEmpty() && it.path == null && it.ref == null && it.branch == null },
    workingDirectory = workingDirectory.orNull(),
    setupCreate = setupCreate.mapNotNull { it.orNull() },
    setupStart = setupStart.mapNotNull { it.orNull() },
    requiredVariables = requiredVariables.mapNotNull { it.orNull() },
    requireGitHub = requireGitHub,
    gitEmailDomain = gitEmailDomain.orNull(),
)
