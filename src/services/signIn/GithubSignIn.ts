// SPDX-License-Identifier: AGPL-3.0-only
import type {Session} from "@/stores/session"

export type SignInFailure =
    /** The browser came back from another sign-in than the one this app started. */
    | "state-mismatch"
    /** The server or GitHub said no (denied access, unknown organisation…). */
    | "denied"
    /** The server did not accept the code, or could not be reached. */
    | "exchange-failed"

export type SignInOutcome =
    /** With the key to keep behind the biometrics, when the server gives one (server#75). */
    | {readonly kind: "signed-in"; readonly session: Session; readonly unlockKey?: string}
    | {readonly kind: "cancelled"}
    | {readonly kind: "failed"; readonly reason: SignInFailure}

/** Signs in to an Ostrom server with GitHub, in the system browser, and comes back by deep link. */
export interface GithubSignIn {
    signIn(serverUrl: string): Promise<SignInOutcome>
}
