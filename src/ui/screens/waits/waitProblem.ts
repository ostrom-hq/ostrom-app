// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError} from "@/api/http/ApiError"
import type {UnlockOutcome} from "@/services/unlock/PhoneUnlock"

/** Why an answer did not go through, as the person reads it (`waits.problems.*`). */
export type WaitProblem =
    | "biometrics-unavailable"
    | "biometrics-failed"
    /** The server did not take this phone's unlock key: signing in again gives a new one. */
    | "unlock-refused"
    /** The unlock could not reach the server: it comes before the answer, so nothing was sent. */
    | "unlock-offline"
    | "org-required"
    /** 400: the server did not accept the values (it checks again what the phone checked). */
    | "invalid"
    /** 403: this phone's credential may not answer waits, or this wait is not the person's. */
    | "forbidden"
    | "offline"
    | "session-expired"
    | "failed"

/** The unlock did not confirm: nothing was sent. */
export function unlockOutcomeProblem(outcome: Exclude<UnlockOutcome, "confirmed" | "cancelled">): WaitProblem {
    switch (outcome) {
        case "unavailable":
            return "biometrics-unavailable"
        case "refused":
            return "unlock-refused"
        case "failed":
            return "biometrics-failed"
    }
}

/** The unlock itself threw (offline, a 5xx, the keychain): nothing was sent, whatever the error. */
export function unlockErrorProblem(error: unknown): WaitProblem {
    if (!(error instanceof ApiError)) return "failed"
    switch (error.problem.kind) {
        case "network":
        case "timeout":
            return "unlock-offline"
        case "unauthorized":
            return "session-expired"
        case "refused":
        case "unexpected-status":
        case "server":
        case "invalid-response":
            return "failed"
    }
}

/**
 * The answer's own failure: the server's refusal told by its status, or the network. `closed` (409 `wait_closed`:
 * someone answered first, or the wait was withdrawn) is no problem of the card but a notice of the screen.
 */
export function answerProblem(error: unknown): WaitProblem | "closed" {
    if (!(error instanceof ApiError)) return "failed"
    const {problem} = error
    switch (problem.kind) {
        case "refused":
            if (problem.type === "org_required") return "org-required"
            if (problem.status === 409) return "closed"
            if (problem.status === 403) return "forbidden"
            if (problem.status === 400) return "invalid"
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
