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
    /** The account API of the server being signed in to (no token yet). */
    readonly accountOf: (serverUrl: string) => AccountRepository
}

const CallbackSchema = z.union([
    z.object({code: z.string().min(1), state: z.string()}),
    z.object({error: z.string(), state: z.string().nullish()}),
])

function isSuccess(result: AuthSessionResult): result is {readonly type: "success"; readonly url: string} {
    return result.type === "success" && "url" in result
}

export class GithubSignInImpl implements GithubSignIn {
    constructor(private readonly dependencies: GithubSignInDependencies) {}

    async signIn(serverUrl: string): Promise<SignInOutcome> {
        const pkce = createPkce(this.dependencies.randomBytes)
        const state = base64Url(this.dependencies.randomBytes(16))
        const start = new URL("/api/auth/github/start", serverUrl)
        start.searchParams.set("redirect_uri", REDIRECT_URI)
        start.searchParams.set("state", state)
        start.searchParams.set("code_challenge", pkce.challenge)
        start.searchParams.set("code_challenge_method", "S256")

        const result = await this.dependencies.openAuthSession(start.toString(), REDIRECT_URI)
        if (!isSuccess(result)) return {kind: "cancelled"}
        const query: Record<string, string> = {}
        new URL(result.url).searchParams.forEach((value, key) => {
            query[key] = value
        })
        const callback = CallbackSchema.safeParse(query)
        if (!callback.success) return {kind: "failed", reason: "denied"}
        if (callback.data.state !== state) return {kind: "failed", reason: "state-mismatch"}
        if (!("code" in callback.data)) return {kind: "failed", reason: "denied"}

        try {
            const token = await this.dependencies
                .accountOf(serverUrl)
                .exchangeCode({code: callback.data.code, codeVerifier: pkce.verifier, redirectUri: REDIRECT_URI})
            return {kind: "signed-in", session: {serverUrl, token: token.token, user: token.user}}
        } catch {
            return {kind: "failed", reason: "exchange-failed"}
        }
    }
}
