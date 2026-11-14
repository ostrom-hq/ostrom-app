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
            CloudSessionRefusal.INVALID -> "The server refused these values: check the repositories, paths, refs, commands and variable names."
            CloudSessionRefusal.PLAN_LIMIT -> "Your plan's cloud sessions at once, or its hours of this period, are used up. Pause or stop one, then try again."
            CloudSessionRefusal.STATE -> "This session's state does not allow it now: it may be over."
            CloudSessionRefusal.FORBIDDEN -> "Cloud sessions are not open to you on this server."
            CloudSessionRefusal.PROFILE_REQUIRED -> "Several cloud profiles, or none, are open to you: start this session from the console."
            CloudSessionRefusal.PROFILE_UNAVAILABLE -> "Your cloud profile cannot be used for this session. Ask an administrator."
            CloudSessionRefusal.CLUSTER_UNAVAILABLE -> "The cloud cluster cannot run sessions now. Ask an administrator."
        },
    )
    /** A field of the new cloud session breaks [problem]: nothing was sent. */
    class CloudSessionInvalid(val problem: CloudSessionProblem) : AppException(
        when (problem) {
            CloudSessionProblem.NAME -> "Name the session in up to 120 characters."
            CloudSessionProblem.PROMPT -> "Write a first message of up to 20,000 characters."
            CloudSessionProblem.REPOSITORY -> "Use an https://github.com/<owner>/<name> repository address."
            CloudSessionProblem.TOO_MANY -> "Use at most 16 repositories, and 16 commands per setup list."
            CloudSessionProblem.VARIABLES -> "Name each required variable once, in UPPER_SNAKE_CASE."
        },
    )
    /** The server refused with [code] (its stable error key, shown only when it is one) and [statusCode]. */
    class Server(val statusCode: Int, val code: String?) :
        AppException("The server returned HTTP $statusCode" + (visibleCode(code)?.let { " ($it)" } ?: "") + ".")
}

/** Error codes are short snake_case identifiers; anything else from the server is not shown. */
private val CODE = Regex("[a-z][a-z0-9_]{0,63}")

/** [code] when it is safe to show, null otherwise. */
fun visibleCode(code: String?): String? = code?.takeIf { it.matches(CODE) }
