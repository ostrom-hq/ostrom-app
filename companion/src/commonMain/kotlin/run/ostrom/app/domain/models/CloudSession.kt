// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/CloudSession.kt; see NOTICE.
package run.ostrom.app.domain.models

/** The server's cloud session statuses (`runners.cloud.v1`); [UNKNOWN] for one a newer server added. */
enum class CloudSessionStatus(val wire: String) {
    CREATING("creating"),
    RUNNING("running"),
    PAUSING("pausing"),
    PAUSED("paused"),
    RESUMING("resuming"),
    TERMINATED("terminated"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    ;

    /** Terminated or failed: nothing more happens to it, its workspace is gone. */
    val ended: Boolean get() = this == TERMINATED || this == FAILED

    companion object {
        fun of(wire: String?): CloudSessionStatus = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** The preparation steps a runner reports while a session is creating or resuming (`runners.cloud.environment.v1`). */
enum class CloudPhase(val wire: String) {
    CONFIGURING_GIT("configuring_git"),
    CLONING_REPOSITORIES("cloning_repositories"),
    FETCHING_REPOSITORIES("fetching_repositories"),
    RUNNING_SETUP("running_setup"),
    ;

    companion object {
        /** Null for a phase this app does not word (`scheduling`, or one a newer server added). */
        fun of(wire: String?): CloudPhase? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Why a session failed or paused on its own: [code] is the stable key (`preparation_failed`, `idle_timeout`...). Only a
 * `preparation_failed`'s [message] is shown (it says which step failed); every other code is worded by the app.
 * [retryable]: for `preparation_failed`, a resume tries again; when false the recipe or the variables must be fixed.
 */
data class CloudFailure(val code: String, val message: String, val retryable: Boolean?) {
    val preparation: Boolean get() = code == PREPARATION_FAILED

    companion object {
        const val PREPARATION_FAILED = "preparation_failed"
    }
}

/** The bounds a session runs under, from its organization's plan. */
data class CloudLimits(val idleTimeoutMinutes: Int, val maxRunMinutes: Int, val restrictedEgress: Boolean)

/** A repository the runner clones into `/workspace/<path>`, at [ref], with a local [branch] made there. */
data class CloudRepository(val url: String, val path: String? = null, val ref: String? = null, val branch: String? = null)

/**
 * A session's preparation recipe (`runners.cloud.environment.v1`), fixed at creation and holding no secret: the
 * repositories, the working directory, the setup commands ([setupCreate] once per workspace, [setupStart] at every
 * execution), the variables the owner must be assigned, whether a GitHub link is required, the Git e-mail domain.
 */
data class CloudRecipe(
    val repositories: List<CloudRepository> = emptyList(),
    val workingDirectory: String? = null,
    val setupCreate: List<String> = emptyList(),
    val setupStart: List<String> = emptyList(),
    val requiredVariables: List<String> = emptyList(),
    val requireGitHub: Boolean = false,
    val gitEmailDomain: String? = null,
) {
    val empty: Boolean get() = this == CloudRecipe()
}

/**
 * One of the person's cloud sessions, as the server describes it (times in epoch milliseconds, null when unreadable).
 * [desiredStatus] is what the owner asked for (`running`, `paused`, `terminated`); [phase] the open label of the current
 * step.
 */
data class CloudSession(
    val id: String,
    val name: String?,
    val agent: String,
    val model: String?,
    val repo: String?,
    val ref: String?,
    val status: CloudSessionStatus,
    val desiredStatus: String?,
    val phase: String?,
    val failure: CloudFailure?,
    val limits: CloudLimits?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val readyAt: Long?,
    val lastActivityAt: Long?,
    val pausedAt: Long?,
    val endedAt: Long?,
    val recipe: CloudRecipe?,
) {
    /** Creating or resuming: the runner prepares the workspace, and [phase] says which step. */
    val preparing: Boolean get() = status == CloudSessionStatus.CREATING || status == CloudSessionStatus.RESUMING

    /** The step being prepared, when the app words it; null otherwise or once prepared. */
    val preparationPhase: CloudPhase? get() = if (preparing) CloudPhase.of(phase) else null

    /** Pause keeps the workspace: offered while it runs or is about to, unless a pause or a stop is asked already. */
    val canPause: Boolean get() = status in PAUSABLE && desiredStatus != PAUSED && desiredStatus != TERMINATED

    /**
     * Resume starts a new execution on the kept workspace: offered on a paused session (or one pausing), unless a resume
     * is asked already (the server then resumes it by itself) or a stop.
     */
    val canResume: Boolean get() = (status == CloudSessionStatus.PAUSED || status == CloudSessionStatus.PAUSING) && desiredStatus != RUNNING && desiredStatus != TERMINATED

    /** Stop ends it for good and deletes its workspace: offered until it ended or a stop is asked already. */
    val canStop: Boolean get() = !status.ended && desiredStatus != TERMINATED

    companion object {
        const val RUNNING = "running"
        const val PAUSED = "paused"
        const val TERMINATED = "terminated"
        private val PAUSABLE = setOf(CloudSessionStatus.CREATING, CloudSessionStatus.RUNNING, CloudSessionStatus.RESUMING)
    }
}

/** A page of cloud sessions, newest first; [next] is the cursor of the older page, null on the last one. */
data class CloudSessionPage(val sessions: List<CloudSession>, val next: String?)

/** The agents a cloud session starts; `shell` is a plain shell, to prepare a workspace or debug an image. */
enum class CloudAgent(val wire: String) { CLAUDE("claude"), CODEX("codex"), OPENCODE("opencode"), GEMINI("gemini"), SHELL("shell") }

/**
 * A cloud session to start: the [agent], an optional [name], [model] and first message ([prompt]), and either the single
 * [repo] at [ref] or a [recipe] (on a server with `runners.cloud.environment.v1`).
 */
data class NewCloudSession(
    val agent: CloudAgent,
    val name: String? = null,
    val model: String? = null,
    val prompt: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val recipe: CloudRecipe? = null,
)

/** Why the server refused to start, pause, resume or stop a cloud session, by its error key. */
enum class CloudSessionRefusal {
    /** `400 invalid_request`: a field breaks a rule (a repository, a path, a ref, a command, a variable name). */
    INVALID,

    /** `402 payment_required`: the plan's sessions at once, or its hours of the period, are used up. */
    PLAN_LIMIT,

    /** `409 conflict`: the session's state does not allow it (it is over). */
    STATE,

    /** `403 forbidden`: cloud sessions are not open to this person or credential on this server. */
    FORBIDDEN,

    /** `422 profile_required`: the person may use no cloud profile, or several, and the app names none. */
    PROFILE_REQUIRED,

    /** `cloud_profile_not_found`, `profile_unassigned`, `image_not_allowed`: the profile cannot be used. */
    PROFILE_UNAVAILABLE,

    /** `cluster_unavailable`, `namespace_not_allowed`: the profile's cluster cannot run sessions now. */
    CLUSTER_UNAVAILABLE,
}

/** A field of the new cloud session form that breaks a rule the server checks: nothing was sent. */
enum class CloudSessionProblem {
    /** The name is over 120 characters or holds a control character. */
    NAME,

    /** The first message is over 20,000 characters. */
    PROMPT,

    /** A repository is not an `https://github.com/<owner>/<name>` URL. */
    REPOSITORY,

    /** More than 16 repositories, or setup commands in a list, or a command over 4,096 characters. */
    TOO_MANY,

    /** More than 64 required variables, one not in UPPER_SNAKE_CASE, or one named twice. */
    VARIABLES,

    /** The Git e-mail domain is not a lowercase DNS name. */
    EMAIL_DOMAIN,
}
