/** Why a call to the Ostrom server did not give the expected answer. Screens switch on `kind`. */
export type ApiProblem =
    /** The phone could not reach the server, or the connection broke while reading the answer. */
    | {readonly kind: "network"}
    /** The server did not answer in time. For a decision, its outcome is unknown: read the request again. */
    | {readonly kind: "timeout"}
    /** The token is missing, expired or revoked: sign in again. `type` is the server's key when it gave one. */
    | {readonly kind: "unauthorized"; readonly type?: string}
    /**
     * The server refused on purpose; `type` is the stable key of CONVENTIONS § 3.3 (`self_approval_refused`…).
     * `requestBound`: a `step_up_required` that only an unlock made for this very request lifts (Q-APP-STEPUP-RISK).
     */
    | {
          readonly kind: "refused"
          readonly status: number
          readonly type: string
          readonly message: string
          readonly requestBound?: true
      }
    /** A 4xx without the contract's error body (a proxy, a missing route). */
    | {readonly kind: "unexpected-status"; readonly status: number}
    /** The server failed (5xx). */
    | {readonly kind: "server"; readonly status: number}
    /** The answer does not match the contract: never shown as data. */
    | {readonly kind: "invalid-response"; readonly detail: string}

export class ApiError extends Error {
    readonly problem: ApiProblem

    constructor(problem: ApiProblem, options?: {cause?: unknown}) {
        super(`Ostrom API: ${problem.kind}`, options)
        this.name = "ApiError"
        this.problem = problem
    }
}

/** The refusal key of an error, when it is a deliberate refusal by the server. */
export function refusalType(error: unknown): string | undefined {
    return error instanceof ApiError && error.problem.kind === "refused" ? error.problem.type : undefined
}

/**
 * The server wants the organization named (409 `org_required`, contract 0.1.142): the person is a member of several,
 * and this session has none. Screens say so, instead of a plain "could not be loaded".
 */
export function isOrgRequired(error: unknown): boolean {
    return refusalType(error) === "org_required"
}
