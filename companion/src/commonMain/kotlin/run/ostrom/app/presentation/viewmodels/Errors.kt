// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/Errors.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.GitHubRefusal
import run.ostrom.app.domain.models.LinkRefusal
import run.ostrom.app.domain.models.TransferProblem
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.domain.models.visibleCode
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.error_check_input
import run.ostrom.app.resources.error_file_refused
import run.ostrom.app.resources.error_file_refused_code
import run.ostrom.app.resources.error_cloud_cluster_unavailable
import run.ostrom.app.resources.error_cloud_email_domain
import run.ostrom.app.resources.error_cloud_effort
import run.ostrom.app.resources.error_cloud_message
import run.ostrom.app.resources.error_cloud_model
import run.ostrom.app.resources.error_cloud_forbidden
import run.ostrom.app.resources.error_cloud_invalid
import run.ostrom.app.resources.error_cloud_name
import run.ostrom.app.resources.error_cloud_plan_limit
import run.ostrom.app.resources.error_cloud_profile_required
import run.ostrom.app.resources.error_cloud_profile_unavailable
import run.ostrom.app.resources.error_cloud_prompt
import run.ostrom.app.resources.error_cloud_repository
import run.ostrom.app.resources.error_cloud_state
import run.ostrom.app.resources.error_cloud_too_many
import run.ostrom.app.resources.error_cloud_variables
import run.ostrom.app.resources.error_cloud_github_not_paused
import run.ostrom.app.resources.error_cloud_github_owner_only
import run.ostrom.app.resources.error_credential_storage
import run.ostrom.app.resources.error_github_account_in_use
import run.ostrom.app.resources.error_github_app_misconfigured
import run.ostrom.app.resources.error_github_app_not_configured
import run.ostrom.app.resources.error_github_device_flow_disabled
import run.ostrom.app.resources.error_github_unreachable
import run.ostrom.app.resources.error_generic
import run.ostrom.app.resources.error_invalid_origin
import run.ostrom.app.resources.error_link_not_artifact
import run.ostrom.app.resources.error_link_signed_out
import run.ostrom.app.resources.error_link_unknown_server
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
import run.ostrom.app.resources.error_transfer_owner
import run.ostrom.app.resources.error_transfer_recipient
import run.ostrom.app.resources.error_transfer_refused
import run.ostrom.app.resources.transfer_refused_other
import run.ostrom.app.resources.error_step_up
import run.ostrom.app.resources.error_unauthorized
import run.ostrom.app.resources.error_unreachable
import run.ostrom.app.resources.error_unsupported
import run.ostrom.app.resources.error_unsupported_api
import run.ostrom.app.resources.error_untrusted_page
import run.ostrom.app.resources.error_workflow_forbidden
import run.ostrom.app.resources.error_workflow_input_missing
import run.ostrom.app.resources.error_workflow_invalid_inputs
import run.ostrom.app.resources.error_workflow_not_runnable
import run.ostrom.app.resources.error_workflow_run_busy
import run.ostrom.app.resources.error_workflow_run_ended
import run.ostrom.app.resources.error_workflow_run_limit
import run.ostrom.app.resources.wfe_error_confirmation_limit
import run.ostrom.app.resources.wfe_error_editors_only
import run.ostrom.app.resources.wfe_error_id_taken
import run.ostrom.app.resources.wfe_error_in_use
import run.ostrom.app.resources.wfe_error_invalid
import run.ostrom.app.resources.wfe_error_limit
import run.ostrom.app.resources.wfe_error_name_taken
import run.ostrom.app.resources.wfe_error_problems
import run.ostrom.app.resources.wfe_error_stale

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
    is AppException.WorkflowRefused -> when (reason) {
        WorkflowRefusal.FORBIDDEN -> Res.string.error_workflow_forbidden
        WorkflowRefusal.NOT_RUNNABLE -> Res.string.error_workflow_not_runnable
        WorkflowRefusal.RUN_LIMIT -> Res.string.error_workflow_run_limit
        WorkflowRefusal.INVALID_INPUTS -> Res.string.error_workflow_invalid_inputs
        WorkflowRefusal.RUN_ENDED -> Res.string.error_workflow_run_ended
        WorkflowRefusal.RUN_BUSY -> Res.string.error_workflow_run_busy
    } to emptyList()
    is AppException.WorkflowInputMissing -> Res.string.error_workflow_input_missing to listOf<Any>(label)
    is AppException.WorkflowEditRefused -> when (reason) {
        WorkflowEditRefusal.EDITORS_ONLY -> Res.string.wfe_error_editors_only
        WorkflowEditRefusal.STALE -> Res.string.wfe_error_stale
        WorkflowEditRefusal.NAME_TAKEN -> Res.string.wfe_error_name_taken
        WorkflowEditRefusal.ID_TAKEN -> Res.string.wfe_error_id_taken
        WorkflowEditRefusal.LIMIT -> Res.string.wfe_error_limit
        WorkflowEditRefusal.CONFIRMATION_LIMIT -> Res.string.wfe_error_confirmation_limit
        WorkflowEditRefusal.INVALID -> Res.string.wfe_error_invalid
        WorkflowEditRefusal.IN_USE -> Res.string.wfe_error_in_use
    } to emptyList()
    is AppException.WorkflowInvalid -> Res.string.wfe_error_problems to listOf<Any>(problems.size)
    is AppException.CloudSessionRefused -> when (reason) {
        CloudSessionRefusal.INVALID -> Res.string.error_cloud_invalid
        CloudSessionRefusal.PLAN_LIMIT -> Res.string.error_cloud_plan_limit
        CloudSessionRefusal.STATE -> Res.string.error_cloud_state
        CloudSessionRefusal.FORBIDDEN -> Res.string.error_cloud_forbidden
        CloudSessionRefusal.PROFILE_REQUIRED -> Res.string.error_cloud_profile_required
        CloudSessionRefusal.PROFILE_UNAVAILABLE -> Res.string.error_cloud_profile_unavailable
        CloudSessionRefusal.CLUSTER_UNAVAILABLE -> Res.string.error_cloud_cluster_unavailable
        CloudSessionRefusal.GITHUB_OWNER_ONLY -> Res.string.error_cloud_github_owner_only
        CloudSessionRefusal.GITHUB_NOT_PAUSED -> Res.string.error_cloud_github_not_paused
    } to emptyList()
    is AppException.FileRefused -> visibleCode(code)?.let { Res.string.error_file_refused_code to listOf<Any>(it) } ?: (Res.string.error_file_refused to emptyList())
    is AppException.TransferRefused -> visibleCode(code)?.let { Res.string.transfer_refused_other to listOf<Any>(it) } ?: (Res.string.error_transfer_refused to emptyList())
    is AppException.TransferInvalid -> when (problem) {
        TransferProblem.RECIPIENT -> Res.string.error_transfer_recipient
        TransferProblem.OWNER -> Res.string.error_transfer_owner
    } to emptyList()
    is AppException.GitHubRefused -> when (reason) {
        GitHubRefusal.APP_NOT_CONFIGURED -> Res.string.error_github_app_not_configured
        GitHubRefusal.DEVICE_FLOW_DISABLED -> Res.string.error_github_device_flow_disabled
        GitHubRefusal.APP_MISCONFIGURED -> Res.string.error_github_app_misconfigured
        GitHubRefusal.ACCOUNT_IN_USE -> Res.string.error_github_account_in_use
        GitHubRefusal.UNREACHABLE -> Res.string.error_github_unreachable
    } to emptyList()
    is AppException.CloudSessionInvalid -> when (problem) {
        CloudSessionProblem.NAME -> Res.string.error_cloud_name
        CloudSessionProblem.PROMPT -> Res.string.error_cloud_prompt
        CloudSessionProblem.REPOSITORY -> Res.string.error_cloud_repository
        CloudSessionProblem.TOO_MANY -> Res.string.error_cloud_too_many
        CloudSessionProblem.VARIABLES -> Res.string.error_cloud_variables
        CloudSessionProblem.EMAIL_DOMAIN -> Res.string.error_cloud_email_domain
        CloudSessionProblem.MESSAGE -> Res.string.error_cloud_message
        CloudSessionProblem.MODEL -> Res.string.error_cloud_model
        CloudSessionProblem.EFFORT -> Res.string.error_cloud_effort
    } to emptyList()
    is AppException.LinkRefused -> when (reason) {
        LinkRefusal.NOT_AN_ARTIFACT -> Res.string.error_link_not_artifact
        LinkRefusal.UNKNOWN_SERVER -> Res.string.error_link_unknown_server
        LinkRefusal.SIGNED_OUT -> Res.string.error_link_signed_out
    } to emptyList()
    is AppException.Server -> visibleCode(code)?.let { Res.string.error_server_http_code to listOf<Any>(statusCode, it) }
        ?: (Res.string.error_server_http to listOf<Any>(statusCode))
}
