// SPDX-License-Identifier: Apache-2.0
// The cases of the React Native app's decisionProblem.test.ts, in Kotlin.
package run.ostrom.app.domain.approvals

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.domain.models.UnlockOutcome
import run.ostrom.app.domain.models.decisionProblem
import run.ostrom.app.domain.models.problem
import run.ostrom.app.domain.models.unlockProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecisionProblemTest {
    @Test
    fun everyRefusalOfAnAnswerIsWordedByItsKey() {
        val cases = listOf(
            AppException.Server(409, "shown_digest_mismatch") to DecisionProblem.CHANGED,
            AppException.Server(409, "conflict") to DecisionProblem.ALREADY_DECIDED,
            AppException.OrganizationRequired() to DecisionProblem.ORG_REQUIRED,
            AppException.StepUpRequired(requestBound = false) to DecisionProblem.STEP_UP,
            AppException.StepUpRequired(requestBound = true) to DecisionProblem.UNLOCK_AGAIN,
            AppException.Server(403, "self_approval_refused") to DecisionProblem.SELF_APPROVAL,
            AppException.Server(403, "forbidden") to DecisionProblem.FORBIDDEN,
            AppException.Server(403, null) to DecisionProblem.FAILED,
            AppException.Server(400, "invalid_request") to DecisionProblem.FAILED,
            AppException.Server(500, null) to DecisionProblem.FAILED,
            AppException.Unreachable() to DecisionProblem.OFFLINE,
            AppException.Unauthorized() to DecisionProblem.SESSION_EXPIRED,
            AppException.NotFound() to DecisionProblem.FAILED,
            IllegalStateException("bug") to DecisionProblem.FAILED,
        )
        for ((error, expected) in cases) assertEquals(expected, error.decisionProblem(), "$error")
    }

    @Test
    fun aFailedUnlockSaysNothingWasSent() {
        assertEquals(DecisionProblem.UNLOCK_OFFLINE, AppException.Unreachable().unlockProblem())
        assertEquals(DecisionProblem.SESSION_EXPIRED, AppException.Unauthorized().unlockProblem())
        assertEquals(DecisionProblem.FAILED, AppException.Server(502, null).unlockProblem())
        assertEquals(DecisionProblem.FAILED, AppException.CredentialStorage().unlockProblem())
    }

    @Test
    fun theUnlockOutcomesAreWorded() {
        assertNull(UnlockOutcome.CONFIRMED.problem())
        assertNull(UnlockOutcome.CANCELLED.problem())
        assertEquals(DecisionProblem.BIOMETRICS_UNAVAILABLE, UnlockOutcome.UNAVAILABLE.problem())
        assertEquals(DecisionProblem.BIOMETRICS_FAILED, UnlockOutcome.FAILED.problem())
        assertEquals(DecisionProblem.UNLOCK_REFUSED, UnlockOutcome.REFUSED.problem())
    }
}
