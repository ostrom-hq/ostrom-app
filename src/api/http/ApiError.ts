/** Why a call to the Ostrom server did not give the expected answer. Screens switch on `kind`. */
export type ApiProblem =
    /** The phone could not reach the server. */
    | {readonly kind: "network"}
    /** The token is missing, expired or revoked: sign in again. */
    | {readonly kind: "unauthorized"}
    /** The server refused on purpose; `type` is the stable key of CONVENTIONS § 3.3 (`self_approval_refused`…). */
    | {readonly kind: "refused"; readonly status: number; readonly type: string; readonly message: string}
    /** The server failed (5xx), or refused without the contract's error body. */
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
