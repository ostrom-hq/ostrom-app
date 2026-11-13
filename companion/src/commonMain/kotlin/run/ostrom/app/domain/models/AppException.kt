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
    /** The server refused with [code] (its stable error key, shown only when it is one) and [statusCode]. */
    class Server(val statusCode: Int, val code: String?) :
        AppException("The server returned HTTP $statusCode" + (visibleCode(code)?.let { " ($it)" } ?: "") + ".")
}

/** Error codes are short snake_case identifiers; anything else from the server is not shown. */
private val CODE = Regex("[a-z][a-z0-9_]{0,63}")

/** [code] when it is safe to show, null otherwise. */
fun visibleCode(code: String?): String? = code?.takeIf { it.matches(CODE) }
