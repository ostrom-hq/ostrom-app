// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion ArtifactScreen.kt, WaitCards.kt and PermissionCard.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.screens

import org.jetbrains.compose.resources.StringResource
import run.ostrom.app.domain.models.ActionKind
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.request_approve
import run.ostrom.app.resources.request_approve_deploy
import run.ostrom.app.resources.request_approve_merge
import run.ostrom.app.resources.request_approve_push
import run.ostrom.app.resources.request_problem_already_decided
import run.ostrom.app.resources.request_problem_biometrics_failed
import run.ostrom.app.resources.request_problem_biometrics_unavailable
import run.ostrom.app.resources.request_problem_changed
import run.ostrom.app.resources.request_problem_failed
import run.ostrom.app.resources.request_problem_forbidden
import run.ostrom.app.resources.request_problem_offline
import run.ostrom.app.resources.request_problem_org_required
import run.ostrom.app.resources.request_problem_self_approval
import run.ostrom.app.resources.request_problem_session_expired
import run.ostrom.app.resources.request_problem_step_up
import run.ostrom.app.resources.request_problem_unlock_again
import run.ostrom.app.resources.request_problem_unlock_offline
import run.ostrom.app.resources.request_problem_unlock_refused

/** "Approve push", "Approve merge", "Approve deploy": the button names what the yes lets through. */
internal fun approveLabel(kind: ActionKind?): StringResource = when (kind) {
    ActionKind.PUSH -> Res.string.request_approve_push
    ActionKind.MERGE -> Res.string.request_approve_merge
    ActionKind.DEPLOY -> Res.string.request_approve_deploy
    else -> Res.string.request_approve
}

internal fun DecisionProblem.words(): StringResource = when (this) {
    DecisionProblem.BIOMETRICS_UNAVAILABLE -> Res.string.request_problem_biometrics_unavailable
    DecisionProblem.BIOMETRICS_FAILED -> Res.string.request_problem_biometrics_failed
    DecisionProblem.UNLOCK_REFUSED -> Res.string.request_problem_unlock_refused
    DecisionProblem.UNLOCK_OFFLINE -> Res.string.request_problem_unlock_offline
    DecisionProblem.STEP_UP -> Res.string.request_problem_step_up
    DecisionProblem.UNLOCK_AGAIN -> Res.string.request_problem_unlock_again
    DecisionProblem.SELF_APPROVAL -> Res.string.request_problem_self_approval
    DecisionProblem.ORG_REQUIRED -> Res.string.request_problem_org_required
    DecisionProblem.CHANGED -> Res.string.request_problem_changed
    DecisionProblem.ALREADY_DECIDED -> Res.string.request_problem_already_decided
    DecisionProblem.FORBIDDEN -> Res.string.request_problem_forbidden
    DecisionProblem.OFFLINE -> Res.string.request_problem_offline
    DecisionProblem.SESSION_EXPIRED -> Res.string.request_problem_session_expired
    DecisionProblem.FAILED -> Res.string.request_problem_failed
}
