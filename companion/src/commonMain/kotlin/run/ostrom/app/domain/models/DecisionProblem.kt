// SPDX-License-Identifier: Apache-2.0
// The React Native app's decisionProblem.ts, in Kotlin.
package run.ostrom.app.domain.models

/** Why a yes or a no did not go through, as the person reads it (never the server's text). */
enum class DecisionProblem {
    /** No biometrics and no passcode on this device: it cannot be the one that answers. */
    BIOMETRICS_UNAVAILABLE,
    BIOMETRICS_FAILED,

    /** The server did not take this device's unlock key: signing in again gives a new one. */
    UNLOCK_REFUSED,

    /** The unlock could not reach the server: it comes before the answer, so nothing was sent. */
    UNLOCK_OFFLINE,

    /** The yes needs a fresh sign-in: the console, opened on the request, takes it. */
    STEP_UP,

    /** The server did not take the unlock for this yes (too old, or used): saying yes again unlocks again. */
    UNLOCK_AGAIN,

    /** The person asked for this, and someone else can approve it: the server never takes that yes. */
    SELF_APPROVAL,

    /** The person is in several organizations and the account names none (`409 org_required`). */
    ORG_REQUIRED,

    /** The request changed after the screen showed it: read it again before answering. */
    CHANGED,
    ALREADY_DECIDED,
    FORBIDDEN,

    /** The device could not reach the server, or it did not answer: the request is read again. */
    OFFLINE,
    SESSION_EXPIRED,
    FAILED,
}

/** The outcome of proving that the device's person is there before an answer. */
enum class UnlockOutcome {
    CONFIRMED,

    /** The person dismissed the prompt: nothing is sent, nothing is said. */
    CANCELLED,
    UNAVAILABLE,
    FAILED,

    /** The server did not take this device's unlock key (another token, a revoked one). */
    REFUSED,
}

/** What an unlock that did not confirm says; null for a cancel, which says nothing. */
fun UnlockOutcome.problem(): DecisionProblem? = when (this) {
    UnlockOutcome.CONFIRMED, UnlockOutcome.CANCELLED -> null
    UnlockOutcome.UNAVAILABLE -> DecisionProblem.BIOMETRICS_UNAVAILABLE
    UnlockOutcome.FAILED -> DecisionProblem.BIOMETRICS_FAILED
    UnlockOutcome.REFUSED -> DecisionProblem.UNLOCK_REFUSED
}

/** The unlock failed before the answer left: nothing was sent, whatever the failure. */
fun Throwable.unlockProblem(): DecisionProblem = when (this) {
    is AppException.Unreachable -> DecisionProblem.UNLOCK_OFFLINE
    is AppException.Unauthorized -> DecisionProblem.SESSION_EXPIRED
    else -> DecisionProblem.FAILED
}

/**
 * The answer's failure. A `403` is told apart by its key alone: `step_up_required` (a fresh sign-in, or an unlock bound
 * to this request), `self_approval_refused`, or any other key, a "not allowed"; a `403` without the contract's body (a
 * proxy's page) is a plain failure. A `409` is the request changed (`shown_digest_mismatch`) or already answered.
 */
fun Throwable.decisionProblem(): DecisionProblem = when (this) {
    is AppException.StepUpRequired -> if (requestBound) DecisionProblem.UNLOCK_AGAIN else DecisionProblem.STEP_UP
    is AppException.OrganizationRequired -> DecisionProblem.ORG_REQUIRED
    is AppException.Unreachable -> DecisionProblem.OFFLINE
    is AppException.Unauthorized -> DecisionProblem.SESSION_EXPIRED
    is AppException.Server -> when {
        statusCode == CONFLICT && code == SHOWN_DIGEST_MISMATCH -> DecisionProblem.CHANGED
        statusCode == CONFLICT -> DecisionProblem.ALREADY_DECIDED
        statusCode == FORBIDDEN && code == SELF_APPROVAL_REFUSED -> DecisionProblem.SELF_APPROVAL
        statusCode == FORBIDDEN && code != null -> DecisionProblem.FORBIDDEN
        else -> DecisionProblem.FAILED
    }
    else -> DecisionProblem.FAILED
}

private const val CONFLICT = 409
private const val FORBIDDEN = 403
private const val SHOWN_DIGEST_MISMATCH = "shown_digest_mismatch"
private const val SELF_APPROVAL_REFUSED = "self_approval_refused"
