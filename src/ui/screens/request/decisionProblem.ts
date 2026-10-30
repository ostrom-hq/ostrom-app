import {ApiError} from "@/api/http/ApiError"
import type {BiometricOutcome} from "@/services/biometrics/BiometricGate"

/** Why a yes or a no did not go through, as the person reads it. */
export type DecisionProblem =
    /** No biometrics and no passcode on this phone: it cannot be the one that says yes. */
    | "biometrics-unavailable"
    | "biometrics-failed"
    /** The person asked for this, and someone else can approve it: the server never takes that yes. */
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

/**
 * The server answers with the code of its `ErrorKind` (`conflict`, `forbidden`…), and `shown_digest_mismatch` when
 * the request changed. A 403 on a request the person asked for is the self-approval refusal of the contract.
 */
export function decisionProblem(error: unknown, askedByMe: boolean): DecisionProblem {
    if (!(error instanceof ApiError)) return "failed"
    const {problem} = error
    switch (problem.kind) {
        case "refused":
            if (problem.type === "shown_digest_mismatch") return "changed"
            if (problem.status === 409) return "already-decided"
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
