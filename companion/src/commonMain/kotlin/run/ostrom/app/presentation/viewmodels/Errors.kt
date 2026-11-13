// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/Errors.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.visibleCode
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.error_check_input
import run.ostrom.app.resources.error_credential_storage
import run.ostrom.app.resources.error_generic
import run.ostrom.app.resources.error_invalid_origin
import run.ostrom.app.resources.error_no_github
import run.ostrom.app.resources.error_not_found
import run.ostrom.app.resources.error_not_ostrom
import run.ostrom.app.resources.error_organization_required
import run.ostrom.app.resources.error_rate_limited
import run.ostrom.app.resources.error_rate_limited_in
import run.ostrom.app.resources.error_server_http
import run.ostrom.app.resources.error_server_http_code
import run.ostrom.app.resources.error_sign_in_denied
import run.ostrom.app.resources.error_sign_in_expired
import run.ostrom.app.resources.error_sign_in_mismatch
import run.ostrom.app.resources.error_sign_up_closed
import run.ostrom.app.resources.error_step_up
import run.ostrom.app.resources.error_unauthorized
import run.ostrom.app.resources.error_unreachable
import run.ostrom.app.resources.error_unsupported
import run.ostrom.app.resources.error_unsupported_api
import run.ostrom.app.resources.error_untrusted_page

/**
 * A failure shown to the person. [text] is the English sentence (logs, tests); [resource] and [args] word the same
 * sentence in the person's language, read where it is shown ([words]).
 */
data class Failure(val text: String, val resource: StringResource? = null, val args: List<Any> = emptyList())

/** The failure's sentence in the language of the system. */
@Composable
fun Failure.words(): String = resource?.let { stringResource(it, *args.toTypedArray()) } ?: text

/** The sentence shown for a failure: the domain's own messages, never a server-provided text. */
internal fun Throwable.userMessage(): Failure = when (this) {
    is AppException.RateLimited -> retryAfterSeconds?.let { Failure("Too many attempts. Try again in $it s.", Res.string.error_rate_limited_in, listOf(it)) }
        ?: Failure(message ?: GENERIC, Res.string.error_rate_limited)
    is AppException -> sentence().let { (resource, args) -> Failure(message ?: GENERIC, resource, args) }
    is IllegalArgumentException -> message?.let { Failure(it) } ?: Failure("Check what you entered.", Res.string.error_check_input)
    else -> Failure(GENERIC, Res.string.error_generic)
}

private const val GENERIC = "Something went wrong."

private fun AppException.sentence(): Pair<StringResource, List<Any>> = when (this) {
    is AppException.InvalidOrigin -> Res.string.error_invalid_origin to emptyList()
    is AppException.Unreachable -> Res.string.error_unreachable to emptyList()
    is AppException.NotOstrom -> Res.string.error_not_ostrom to emptyList()
    is AppException.NoGitHubSignIn -> Res.string.error_no_github to emptyList()
    is AppException.UnsupportedApi -> Res.string.error_unsupported_api to emptyList()
    is AppException.Unauthorized -> Res.string.error_unauthorized to emptyList()
    is AppException.RateLimited -> Res.string.error_rate_limited to emptyList()
    is AppException.SignInMismatch -> Res.string.error_sign_in_mismatch to emptyList()
    is AppException.SignInDenied -> Res.string.error_sign_in_denied to emptyList()
    is AppException.SignUpClosed -> Res.string.error_sign_up_closed to emptyList()
    is AppException.SignInExpired -> Res.string.error_sign_in_expired to emptyList()
    is AppException.UntrustedSignInPage -> Res.string.error_untrusted_page to emptyList()
    is AppException.OrganizationRequired -> Res.string.error_organization_required to emptyList()
    is AppException.NotFound -> Res.string.error_not_found to emptyList()
    is AppException.Unsupported -> Res.string.error_unsupported to emptyList()
    is AppException.StepUpRequired -> Res.string.error_step_up to emptyList()
    is AppException.CredentialStorage -> Res.string.error_credential_storage to emptyList()
    is AppException.Server -> visibleCode(code)?.let { Res.string.error_server_http_code to listOf<Any>(statusCode, it) }
        ?: (Res.string.error_server_http to listOf<Any>(statusCode))
}
