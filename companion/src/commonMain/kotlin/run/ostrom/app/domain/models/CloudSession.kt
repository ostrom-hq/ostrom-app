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

    /** An accepted transfer is changing the owner at a paused boundary (`runners.cloud.transfer.v1`). */
    TRANSFERRING("transferring"),
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

/**
 * How a cloud session's agent runs, fixed at creation: in a terminal (a PTY the console attaches to) or headless, talked
 * to through its transcript and messages (`runners.cloud.conversation.v1`, claude and codex only).
 */
enum class CloudSessionMode { TERMINAL, CONVERSATION }

/**
 * The agent waits for a person: [reason] is `permission` (a tool use to approve) or `question`; [requestId] the request
 * to answer, [since] server time in milliseconds (null when unreadable).
 */
data class SessionWaiting(val reason: String, val requestId: String?, val since: Long?) {
    companion object {
        const val PERMISSION = "permission"
        const val QUESTION = "question"
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
 * Where a session runs: a pod the server started, a member's device (`runners.cloud.remote.v1`), or the terminal of a
 * member's `ostrom <agent>` ([LOCAL], `runners.cloud.local.v1`): it takes no message and lives as long as the run.
 */
enum class SessionKind(val wire: String) {
    CLOUD("cloud"), REMOTE("remote"), LOCAL("local");

    companion object {
        /** A server without remote sessions sends no kind: its sessions are cloud sessions. */
        fun of(wire: String?): SessionKind = entries.firstOrNull { it.wire == wire } ?: CLOUD
    }
}

/** The kinds a server knows: a server that announces remote sessions knows cloud and remote ones, local ones only when it says so. */
object SessionKinds {
    fun announced(features: Set<String>): Set<SessionKind> = buildSet {
        add(SessionKind.CLOUD); add(SessionKind.REMOTE)
        if (Features.RUNNERS_CLOUD_LOCAL in features) add(SessionKind.LOCAL)
    }
}

/**
 * The device a remote session runs on, as the server describes it now. [removed]: the device was removed, so the
 * session has no host any more. [online]: the host runs the session now. [directoryName] is the folder's name only.
 */
data class SessionHost(
    val deviceName: String?,
    val platform: String?,
    val osVersion: String?,
    val directoryName: String?,
    val online: Boolean,
    val removed: Boolean,
    /** [ASK] or [BYPASS]. */
    val permissionMode: String?,
) {
    companion object {
        /** Claude Code's permission requests come to the organization's clients; Codex stays in its sandbox. */
        const val ASK = "ask"
        const val BYPASS = "bypass"
    }
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
    /** Whose GitHub access it uses (`runners.cloud.github.v1`); null when the server does not say or says something new. */
    val githubAccess: GitHubAccess? = null,
    /** The member who owns it (`runners.cloud.transfer.v1`); null when the server does not say. */
    val ownerId: String? = null,
    /** The accepted transfer the session is moving toward; null when none. */
    val transferId: String? = null,
    /** Where it runs (`runners.cloud.remote.v1`, `runners.cloud.local.v1`); a server without them sends cloud sessions only. */
    val kind: SessionKind = SessionKind.CLOUD,
    /** Where a remote session runs; null for a cloud or a local session. */
    val host: SessionHost? = null,
    /**
     * Why the device's daemon refused to start the session ([failure] `host_refused`): its stable reason code
     * (`folder_missing`, `agent_unsupported`, ...), null when it did not give a readable one.
     */
    val refusalReason: String? = null,
    /** A [CloudSessionMode.CONVERSATION] session is talked to through its transcript and messages; a terminal one is not. */
    val mode: CloudSessionMode = CloudSessionMode.TERMINAL,
    /** The agent's effort (`runners.cloud.effort.v1`), if chosen. */
    val effort: String? = null,
    /** The owner's standing approval of tool uses is on (`runners.cloud.permissions.bypass.v1`). */
    val permissionBypass: Boolean = false,
    /** Why the agent waits for a person (`runners.cloud.permissions.v1`); null when it does not. */
    val waiting: SessionWaiting? = null,
) {
    /** A session on a member's device: it is never transferred, its files stay there. */
    val remote: Boolean get() = kind == SessionKind.REMOTE

    /** The session of an `ostrom <agent>` in a terminal: no pause, resume, transfer or message from the app. */
    val local: Boolean get() = kind == SessionKind.LOCAL

    /** A remote session paused because its device stopped answering (the server's `host_offline`). */
    val hostOffline: Boolean get() = remote && failure?.code == HOST_OFFLINE

    /**
     * A remote session that waits for its device to start it (`phase = waiting_host`): a new one the device's daemon has
     * not picked up yet, or one woken by a message. It starts by itself once the device answers.
     */
    val waitingForHost: Boolean get() = remote && phase == WAITING_HOST && !status.ended && status != CloudSessionStatus.PAUSED

    /** The device's daemon refused to start the session (`host_refused`): it ended without running. */
    val hostRefused: Boolean get() = remote && failure?.code == HOST_REFUSED

    /** A conversation session: its screen shows the transcript and the composer. */
    val conversation: Boolean get() = mode == CloudSessionMode.CONVERSATION

    /** Creating or resuming: the runner prepares the workspace, and [phase] says which step. */
    val preparing: Boolean get() = status == CloudSessionStatus.CREATING || status == CloudSessionStatus.RESUMING

    /** The step being prepared, when the app words it; null otherwise or once prepared. */
    val preparationPhase: CloudPhase? get() = if (preparing) CloudPhase.of(phase) else null

    /**
     * Pause keeps the workspace: offered while it runs or is about to, unless a pause or a stop is asked already. A local
     * session is never paused from the app: it lives as long as its terminal.
     */
    val canPause: Boolean get() = !local && status in PAUSABLE && desiredStatus != PAUSED && desiredStatus != TERMINATED

    /**
     * Resume starts a new execution on the kept workspace: offered on a paused session (or one pausing), unless a resume
     * is asked already (the server then resumes it by itself) or a stop. A remote session resumes only from its own
     * device, which this app never is: a message asks the device to resume it instead (the server refuses an explicit
     * resume from elsewhere); a local session never.
     */
    val canResume: Boolean get() = !remote && !local && (status == CloudSessionStatus.PAUSED || status == CloudSessionStatus.PAUSING) &&
        desiredStatus != RUNNING && desiredStatus != TERMINATED

    /** Stop ends it for good and deletes its workspace: offered until it ended or a stop is asked already. */
    val canStop: Boolean get() = !status.ended && desiredStatus != TERMINATED

    companion object {
        const val RUNNING = "running"
        const val PAUSED = "paused"
        const val TERMINATED = "terminated"
        const val HOST_OFFLINE = "host_offline"
        const val WAITING_HOST = "waiting_host"
        const val HOST_REFUSED = "host_refused"
        private val PAUSABLE = setOf(CloudSessionStatus.CREATING, CloudSessionStatus.RUNNING, CloudSessionStatus.RESUMING)
    }
}

/**
 * Whose GitHub access a cloud session's Git and `gh` use, through the runner (the agent never holds a token): the
 * owner's linked account ([OWNER], the default), the same with the terminal shared ([SHARED]), or none ([NONE]).
 */
enum class GitHubAccess(val wire: String) {
    OWNER("owner"),
    SHARED("shared"),
    NONE("none"),
    ;

    companion object {
        /** Null for a value this app does not know (one a newer server added). */
        fun of(wire: String?): GitHubAccess? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A page of cloud sessions, newest first; [next] is the cursor of the older page, null on the last one.
 * [remoteSessions]: the server announces remote sessions (`runners.cloud.remote.v1`), so each row says where it runs.
 */
data class CloudSessionPage(val sessions: List<CloudSession>, val next: String?, val remoteSessions: Boolean = false)

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
    /** [CloudSessionMode.CONVERSATION] on a server with `runners.cloud.conversation.v1`, for claude and codex only. */
    val mode: CloudSessionMode = CloudSessionMode.TERMINAL,
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

    /** `403 github_owner_only`: only the session's owner, in person, changes its GitHub access. */
    GITHUB_OWNER_ONLY,

    /** `409 session_not_paused`: removing the GitHub access needs the session paused (a running execution may hold a token). */
    GITHUB_NOT_PAUSED,
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

    /** A message to the agent is empty, over 32,000 characters or holds a control character. */
    MESSAGE,

    /** A model is not 1 to 120 letters, digits and `. _ : / -`. */
    MODEL,

    /** An effort is not 1 to 120 letters, digits and `. _ : / -`. */
    EFFORT,

    /** No online device is chosen for a session on a device. */
    CHOOSE_DEVICE,

    /** No folder the device offers is chosen. */
    CHOOSE_FOLDER,

    /** The agent on a device is neither Claude Code nor Codex. */
    CHOOSE_AGENT,
}
