import type {Session} from "@/stores/session"

export type SignInFailure =
    /** The browser came back from another sign-in than the one this app started. */
    | "state-mismatch"
    /** The server or GitHub said no (denied access, unknown organisation…). */
    | "denied"
    /** The server did not accept the code, or could not be reached. */
    | "exchange-failed"

export type SignInOutcome =
    | {readonly kind: "signed-in"; readonly session: Session}
    | {readonly kind: "cancelled"}
    | {readonly kind: "failed"; readonly reason: SignInFailure}

/** Signs in to an Ostrom server with GitHub, in the system browser, and comes back by deep link. */
export interface GithubSignIn {
    signIn(serverUrl: string): Promise<SignInOutcome>
}
