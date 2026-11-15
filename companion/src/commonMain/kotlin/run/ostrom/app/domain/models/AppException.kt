// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion CompanionException (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.models

/** A failure the screens can explain, without any server message or response body. */
sealed class AppException(message: String) : Exception(message) {
    /** The origin is not an `https` origin (or `http` on loopback) without path, query or credentials. */
    class InvalidOrigin : AppException("Enter a server address such as https://ostrom.example.org.")
    class Unreachable : AppException("The server could not be reached.")
    class NotOstrom : AppException("This address does not answer like an Ostrom server.")
    /** The server offers no sign-in this app can do (GitHub). */
    class NoGitHubSignIn : AppException("This server does not offer GitHub sign-in.")
    class UnsupportedApi(val apiVersion: Int?) : AppException("This server speaks another version of the API than this app.")
    class Unauthorized : AppException("The server signed this device out. Sign in again.")
    class RateLimited(val retryAfterSeconds: Long?) : AppException("Too many attempts. Try again later.")
    /** The browser sign-in did not come back with this attempt's answer (another state, an unknown return). */
    class SignInMismatch : AppException("The sign-in did not come back as expected. Try again.")
    /** The provider or the person refused the sign-in. */
    class SignInDenied : AppException("The sign-in was refused.")
    /** An invite-only instance refused to create an account (`signup_closed`). */
    class SignUpClosed : AppException("This Ostrom instance is invite-only.")
    /** The device-flow code expired before the person entered it. */
    class SignInExpired : AppException("The code expired. Start again.")
    /** The server sent a sign-in page that is neither GitHub's nor its own. */
    class UntrustedSignInPage : AppException("The server sent a sign-in page that is neither GitHub's nor its own.")
    /** The person belongs to several organizations and none is chosen yet (`409 org_required`). */
    class OrganizationRequired : AppException("Choose the organization in Settings.")
    /** It does not exist or this person can no longer see it. */
    class NotFound : AppException("This no longer exists, or you can no longer see it.")
    /** The server does not offer what the screen needs ([feature] is the missing capability). */
    class Unsupported(val feature: String) : AppException("This server does not offer this yet.")
    /**
     * A yes the server takes only after a fresh proof (`403 step_up_required`): with [requestBound], an unlock bound to
     * this very request (unlock again, then answer again); otherwise a fresh sign-in, given in the console.
     */
    class StepUpRequired(val requestBound: Boolean) : AppException("Confirm this yes again.")
    class CredentialStorage : AppException("This device's secure storage refused to read or keep the sign-in. Unlock the device and try again.")
    /** The server refused to start or cancel a workflow run, for [reason]. */
    class WorkflowRefused(val reason: WorkflowRefusal) : AppException(
        when (reason) {
            WorkflowRefusal.FORBIDDEN -> "Only an owner or an admin of the organization starts and cancels workflow runs."
            WorkflowRefusal.NOT_RUNNABLE -> "This workflow is not accepted: accept it in the console before running it."
            WorkflowRefusal.RUN_LIMIT -> "Too many runs are unfinished. Wait for one to end, or cancel one, then try again."
            WorkflowRefusal.INVALID_INPUTS -> "The server refused these inputs: check the required ones and their length."
            WorkflowRefusal.RUN_ENDED -> "This run has already ended."
            WorkflowRefusal.RUN_BUSY -> "The run moved meanwhile. Try again."
        },
    )
    /** A required input of a workflow ([label]) has no value. */
    class WorkflowInputMissing(val label: String) : AppException("Fill in $label.")
    /** The server refused to create, save or delete a workflow, for [reason]. */
    class WorkflowEditRefused(val reason: WorkflowEditRefusal) : AppException(
        when (reason) {
            WorkflowEditRefusal.EDITORS_ONLY -> "You may not decide this workflow: ask an owner or an admin, or an editor for a workflow without a gate."
            WorkflowEditRefusal.STALE -> "Someone saved this workflow since you opened it. Reload it to see their version."
            WorkflowEditRefusal.NAME_TAKEN -> "Another workflow of the organization has this name."
            WorkflowEditRefusal.ID_TAKEN -> "A workflow with this id exists. Try again."
            WorkflowEditRefusal.LIMIT -> "The organization keeps as many workflows as it may. Delete one first."
            WorkflowEditRefusal.CONFIRMATION_LIMIT -> "Too many changes wait for a confirmation on the console. Confirm or let some lapse, then try again."
            WorkflowEditRefusal.INVALID -> "The server refused this workflow. Check it, or edit it in the console."
            WorkflowEditRefusal.IN_USE -> "This workflow is in use (a package, an automation or another workflow): remove that use first."
        },
    )
    /** The draft breaks [problems] the server would refuse: nothing was sent. */
    class WorkflowInvalid(val problems: List<WorkflowProblem>) : AppException("The workflow breaks ${problems.size} rule(s); fix them to save.")
    /** The server refused to start, pause, resume or stop a cloud session, for [reason]. */
    class CloudSessionRefused(val reason: CloudSessionRefusal) : AppException(
        when (reason) {
            CloudSessionRefusal.INVALID -> "The server refused these values: check the repositories, paths, refs, commands, variable names and e-mail domain."
            CloudSessionRefusal.PLAN_LIMIT -> "Your plan's cloud sessions at once, or its hours of this period, are used up. Pause or stop one, then try again."
            CloudSessionRefusal.STATE -> "This session's state does not allow it now: it may be over."
            CloudSessionRefusal.FORBIDDEN -> "Cloud sessions are not open to you on this server."
            CloudSessionRefusal.PROFILE_REQUIRED -> "Several cloud profiles, or none, are open to you: start this session from the console."
            CloudSessionRefusal.PROFILE_UNAVAILABLE -> "Your cloud profile cannot be used for this session. Ask an administrator."
            CloudSessionRefusal.CLUSTER_UNAVAILABLE -> "The cloud cluster cannot run sessions now. Ask an administrator."
            CloudSessionRefusal.GITHUB_OWNER_ONLY -> "Only the session's owner, in person, changes its GitHub access."
            CloudSessionRefusal.GITHUB_NOT_PAUSED -> "Pause the session before removing its GitHub access."
        },
    )
    /** The server refused a transfer of a cloud session or a decision on one, with its error key [code] (`transfer_pending`, `recipient_ineligible`...). */
    class TransferRefused(val code: String) :
        AppException("The server refused the transfer" + (visibleCode(code)?.let { " ($it)" } ?: "") + ".")
    /** The transfer form breaks [problem]: nothing was sent. */
    class TransferInvalid(val problem: TransferProblem) : AppException(
        when (problem) {
            TransferProblem.RECIPIENT -> "Choose the member to give the session to."
            TransferProblem.OWNER -> "This member already owns the session."
        },
    )
    /** The server refused to start or follow the GitHub link, for [reason]. */
    class GitHubRefused(val reason: GitHubRefusal) : AppException(
        when (reason) {
            GitHubRefusal.APP_NOT_CONFIGURED -> "An administrator has not configured the instance's GitHub App yet."
            GitHubRefusal.DEVICE_FLOW_DISABLED -> "The instance's GitHub App does not allow the device flow."
            GitHubRefusal.APP_MISCONFIGURED -> "GitHub refused the instance's GitHub App: ask an administrator to check it."
            GitHubRefusal.ACCOUNT_IN_USE -> "This GitHub account is already linked to another member of the organization."
            GitHubRefusal.UNREACHABLE -> "GitHub could not be reached. Try again."
        },
    )
    /** A field of the new cloud session breaks [problem]: nothing was sent. */
    class CloudSessionInvalid(val problem: CloudSessionProblem) : AppException(
        when (problem) {
            CloudSessionProblem.NAME -> "Name the session in up to 120 characters."
            CloudSessionProblem.PROMPT -> "Write a first message of up to 20,000 characters."
            CloudSessionProblem.REPOSITORY -> "Use an https://github.com/<owner>/<name> repository address."
            CloudSessionProblem.TOO_MANY -> "Use at most 16 repositories, and 16 commands of up to 4,096 characters per setup list."
            CloudSessionProblem.VARIABLES -> "Name at most 64 required variables, each once, in UPPER_SNAKE_CASE."
            CloudSessionProblem.EMAIL_DOMAIN -> "Use a lowercase domain name, such as example.com, for the Git e-mail domain."
            CloudSessionProblem.MESSAGE -> "Write a message of up to 32,000 characters."
            CloudSessionProblem.MODEL -> "Use a valid model: letters, digits and . _ : / - (up to 120 characters)."
            CloudSessionProblem.EFFORT -> "Use a valid effort: letters, digits and . _ : / - (up to 120 characters)."
            CloudSessionProblem.CHOOSE_DEVICE -> "Choose one of your online devices."
            CloudSessionProblem.CHOOSE_FOLDER -> "Choose one of the folders the device offers."
            CloudSessionProblem.CHOOSE_AGENT -> "Choose Claude Code or Codex."
        },
    )
    /**
     * The server refused to start a session on one of the member's devices, or to read its folders or conversations,
     * with [code] (`device_offline`, `folder_not_published`...), worded by the app (TeamCode's `DeviceRefused`).
     */
    class DeviceRefused(val code: String) : AppException(
        when (code) {
            DEVICE_OFFLINE -> "This device is offline: its Ostrom service is not connected. Start it on the device (ostrom service install), or choose another device."
            "folder_not_published" -> "This device no longer offers this folder. Add it on the device with ostrom folders add, or choose another folder."
            "device_not_found" -> "This device is not one of yours any more, or it was removed."
            "remote_requires_login" -> "Only the device's owner, signed in with a password, starts a session on it."
            "device_target_unsupported" -> "This server does not start sessions on your devices. Update it to use them."
            "invalid_folder" -> "Choose one of the folders the device offers."
            DAEMON_RESUME_UNSUPPORTED -> "Ostrom on this device is too old to continue a conversation. Update it on the device (ostrom update)."
            "device_no_answer" -> "The device did not answer in time. Try again."
            "resume_unsupported" -> "Only a Claude Code session continues a conversation of the device."
            "invalid_resume" -> "Choose one of the conversations the device listed."
            else -> "The server refused to start the session on this device" + (visibleCode(code)?.let { " ($it)" } ?: "") + "."
        },
    ) {
        companion object {
            const val DEVICE_OFFLINE = "device_offline"
            const val DAEMON_RESUME_UNSUPPORTED = "daemon_resume_unsupported"

            /** The codes the server answers about a device or its folders; any other failure keeps its own type. */
            val CODES = setOf(DEVICE_OFFLINE, "folder_not_published", "device_not_found", "remote_requires_login", "device_target_unsupported", "invalid_folder",
                DAEMON_RESUME_UNSUPPORTED, "device_no_answer", "resume_unsupported", "invalid_resume",
                // The daemon's own reasons for not reading a folder's conversations.
                "folder_not_configured", "folder_missing", "folder_moved", "folder_refused")
        }
    }
    /** An `ostrom://open?url=` link this app does not open, for [reason]: nothing was opened or asked of a server. */
    class LinkRefused(val reason: LinkRefusal) : AppException(
        when (reason) {
            LinkRefusal.NOT_AN_ARTIFACT -> "This link does not lead to an Ostrom artifact."
            LinkRefusal.UNKNOWN_SERVER -> "This artifact is on a server none of your accounts is on. Add that account, then open the link again."
            LinkRefusal.SIGNED_OUT -> "Your account on this artifact's server is signed out. Sign in again, then open the link again."
        },
    )
    /**
     * The server refused a file sent to a session ([code]: `file_too_large`, `upload_quota`, ...; [statusCode] 0 for a
     * check made here, such as `file_integrity_failed`), or the file could not be read (`file_unreadable`).
     */
    class FileRefused(val code: String?, val statusCode: Int, val retryAfterSeconds: Long? = null) :
        AppException("The file was refused" + (visibleCode(code)?.let { " ($it)" } ?: "") + ".")
    /** The server refused with [code] (its stable error key, shown only when it is one) and [statusCode]. */
    class Server(val statusCode: Int, val code: String?) :
        AppException("The server returned HTTP $statusCode" + (visibleCode(code)?.let { " ($it)" } ?: "") + ".")
}

/** Error codes are short snake_case identifiers; anything else from the server is not shown. */
private val CODE = Regex("[a-z][a-z0-9_]{0,63}")

/** [code] when it is safe to show, null otherwise. */
fun visibleCode(code: String?): String? = code?.takeIf { it.matches(CODE) }

/** Why an `ostrom://open?url=` link is not opened. */
enum class LinkRefusal {
    /** Not an `<origin>/artifacts/<id>` page with a readable address, id and version. */
    NOT_AN_ARTIFACT,

    /** No account of this device is on the page's server or its console. */
    UNKNOWN_SERVER,

    /** The accounts on that server are all signed out. */
    SIGNED_OUT,
}
