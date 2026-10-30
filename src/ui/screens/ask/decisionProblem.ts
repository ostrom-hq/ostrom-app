// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError} from "@/api/http/ApiError"
import type {BiometricOutcome} from "@/services/biometrics/BiometricGate"

/** Why a yes or a no did not go through, as the person reads it. */
export type DecisionProblem =
    /** No biometrics and no passcode on this phone: it cannot be the one that says yes. */
    | "biometrics-unavailable"
    | "biometrics-failed"
    /** The server never lets someone approve their own request. */
    | "self-approval"
    /** The request changed after the screen showed it: read it again before deciding. */
    | "changed"
    | "already-decided"
    | "forbidden"
    /** The phone could not reach the server, or it did not answer: the request is read again. */
    | "offline"
    | "session-expired"
    | "failed"

export function biometricProblem(outcome: Exclude<BiometricOutcome, "confirmed" | "cancelled">): DecisionProblem {
    return outcome === "unavailable" ? "biometrics-unavailable" : "biometrics-failed"
}

const REFUSALS: Readonly<Record<string, DecisionProblem>> = {
    self_approval_refused: "self-approval",
    shown_digest_mismatch: "changed",
    already_decided: "already-decided",
}

export function decisionProblem(error: unknown): DecisionProblem {
    if (!(error instanceof ApiError)) return "failed"
    const {problem} = error
    switch (problem.kind) {
        case "refused":
            return REFUSALS[problem.type] ?? (problem.status === 403 ? "forbidden" : "failed")
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

/** After these, what the server holds may differ from the screen: the request is read again. */
export function shouldReload(problem: DecisionProblem): boolean {
    return problem === "changed" || problem === "already-decided" || problem === "offline"
}
