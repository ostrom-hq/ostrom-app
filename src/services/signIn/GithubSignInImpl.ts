// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"
import type {AccountRepository} from "@/repositories/AccountRepository"
import {base64Url, createPkce} from "./pkce"
import type {GithubSignIn, SignInOutcome} from "./GithubSignIn"

export const REDIRECT_URI = "ostrom://auth/callback"

/** What `openAuthSessionAsync` of expo-web-browser gives back, reduced to what sign-in needs. */
export type AuthSessionResult = {readonly type: "success"; readonly url: string} | {readonly type: string}

export interface GithubSignInDependencies {
    readonly openAuthSession: (url: string, redirectUri: string) => Promise<AuthSessionResult>
    readonly randomBytes: (length: number) => Uint8Array
    /** The account API of the server being signed in to: without a token for the exchange, with it to read `me`. */
    readonly accountOf: (serverUrl: string, token?: string) => AccountRepository
}

const CallbackSchema = z.union([
    z.object({code: z.string().min(1), state: z.string()}),
    z.object({error: z.string(), state: z.string().nullish()}),
])

function isSuccess(result: AuthSessionResult): result is {readonly type: "success"; readonly url: string} {
    return result.type === "success" && "url" in result
}

/** The query of a return to {@link REDIRECT_URI}; undefined for any other address, or one that cannot be read. */
function queryOfReturn(url: string): Record<string, string> | undefined {
    let returned: URL
    try {
        returned = new URL(url)
    } catch {
        return undefined
    }
    if (url.split(/[?#]/, 1)[0] !== REDIRECT_URI) return undefined
    const query: Record<string, string> = {}
    returned.searchParams.forEach((value, key) => {
        query[key] = value
    })
    return query
}

export class GithubSignInImpl implements GithubSignIn {
    /**
     * The state of the last sign-in asked, until its browser comes back. Only that sign-in can connect: an earlier one's
     * return, even with its own valid state, connects nothing (AUDIT-SECU-APP-2 A4).
     */
    private awaited: string | undefined

    constructor(private readonly dependencies: GithubSignInDependencies) {}

    async signIn(serverUrl: string): Promise<SignInOutcome> {
        const pkce = createPkce(this.dependencies.randomBytes)
        const state = base64Url(this.dependencies.randomBytes(16))
        this.awaited = state
        const start = new URL("/api/v1/auth/github/start", serverUrl)
        start.searchParams.set("redirect_uri", REDIRECT_URI)
        start.searchParams.set("state", state)
        start.searchParams.set("code_challenge", pkce.challenge)
        start.searchParams.set("code_challenge_method", "S256")

        const result = await this.dependencies.openAuthSession(start.toString(), REDIRECT_URI)
        // Cleared by its return, whatever it is; a later sign-in may have replaced it already.
        const asked = this.awaited === state
        if (asked) this.awaited = undefined
        if (!isSuccess(result)) return {kind: "cancelled"}
        const query = queryOfReturn(result.url)
        if (query === undefined || !asked) return {kind: "failed", reason: "state-mismatch"}
        const callback = CallbackSchema.safeParse(query)
        if (!callback.success) return {kind: "failed", reason: "denied"}
        // A refusal carries nothing to exchange: it is reported as such, with or without its state.
        if (!("code" in callback.data)) return {kind: "failed", reason: "denied"}
        if (callback.data.state !== state) return {kind: "failed", reason: "state-mismatch"}

        try {
            const token = await this.dependencies
                .accountOf(serverUrl)
                .exchangeCode({code: callback.data.code, codeVerifier: pkce.verifier, redirectUri: REDIRECT_URI})
            const session = {serverUrl, token: token.token, user: token.user}
            const orgId = await this.orgOf(serverUrl, token.token)
            const signedIn = {kind: "signed-in", session: orgId === undefined ? session : {...session, orgId}} as const
            return token.unlockKey === undefined ? signedIn : {...signedIn, unlockKey: token.unlockKey}
        } catch {
            return {kind: "failed", reason: "exchange-failed"}
        }
    }

    /**
     * The organization the calls act in from then on (`Ostrom-Org`): the only one the person is a member of. With
     * several, the one `me` names, if any; otherwise none, and the person chooses it in Settings (409 `org_required`
     * says so meanwhile). A server without `GET /me/organizations` is asked `me` alone.
     */
    private async orgOf(serverUrl: string, token: string): Promise<string | undefined> {
        const account = this.dependencies.accountOf(serverUrl, token)
        const organizations = await account.organizations().catch(() => undefined)
        const [only, ...others] = organizations ?? []
        if (only !== undefined && others.length === 0) return only.id
        try {
            return (await account.me()).organization?.id
        } catch {
            return undefined
        }
    }
}
