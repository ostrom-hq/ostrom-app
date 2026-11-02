// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError} from "@/api/http/ApiError"
import type {UnlockOutcome} from "@/services/unlock/PhoneUnlock"

/** Why a yes or a no did not go through, as the person reads it. */
export type DecisionProblem =
    /** No biometrics and no passcode on this phone: it cannot be the one that says yes. */
    | "biometrics-unavailable"
    | "biometrics-failed"
    /** The server did not take this phone's unlock key: signing in again gives a new one. */
    | "unlock-refused"
    /** The unlock could not reach the server: it comes before the answer, so nothing was sent. */
    | "unlock-offline"
    /** The yes needs a sign-in less than 5 minutes old: the console, just opened on the request, takes it. */
    | "step-up"
    /** The person asked for this, and someone else can approve it: the server never takes that yes. */
    | "self-approval"
    /** The person is in several organizations and this session names none (409 `org_required`). */
    | "org-required"
    /** The request changed after the screen showed it: read it again before deciding. */
    | "changed"
    | "already-decided"
    | "forbidden"
    /** The phone could not reach the server, or it did not answer: the request is read again. */
    | "offline"
    | "session-expired"
    | "failed"

export function biometricProblem(outcome: Exclude<UnlockOutcome, "confirmed" | "cancelled">): DecisionProblem {
    switch (outcome) {
        case "unavailable":
            return "biometrics-unavailable"
        case "refused":
            return "unlock-refused"
        case "failed":
            return "biometrics-failed"
    }
}

/** The unlock failed before the answer left: nothing was sent, whatever the error. */
export function unlockProblem(error: unknown): DecisionProblem {
    if (!(error instanceof ApiError)) return "failed"
    switch (error.problem.kind) {
        case "network":
        case "timeout":
            return "unlock-offline"
        case "unauthorized":
            return "session-expired"
        default:
            return "failed"
    }
}

/**
 * The server answers with the code of its `ErrorKind` (`conflict`, `forbidden`…), and `shown_digest_mismatch` when
 * the request changed. A 403 is `step_up_required` (a fresh sign-in is needed for a yes, contract 0.1.140) or, on a
 * request the person asked for, the self-approval refusal of the contract.
 */
export function decisionProblem(error: unknown, askedByMe: boolean): DecisionProblem {
    if (!(error instanceof ApiError)) return "failed"
    const {problem} = error
    switch (problem.kind) {
        case "refused":
            if (problem.type === "shown_digest_mismatch") return "changed"
            if (problem.type === "org_required") return "org-required"
            if (problem.status === 409) return "already-decided"
            if (problem.status === 403 && problem.type === "step_up_required") return "step-up"
            if (problem.status === 403 && problem.type === "self_approval_refused") return "self-approval"
            if (problem.status === 403) return askedByMe ? "self-approval" : "forbidden"
            return "failed"
        case "network":
        case "timeout":
            return "offline"
        case "unauthorized":
            return "session-expired"
        case "unexpected-status":
        case "server":
        case "invalid-response":
            return "failed"
    }
}
