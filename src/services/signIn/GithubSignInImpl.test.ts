// SPDX-License-Identifier: AGPL-3.0-only
import type {TokenExchangeRequest} from "@/api/contract/account"
import type {AccountRepository} from "@/repositories/AccountRepository"
import {ApiError} from "@/api/http/ApiError"
import {pkceChallenge} from "./pkce"
import {type AuthSessionResult, GithubSignInImpl, REDIRECT_URI} from "./GithubSignInImpl"

const SERVER = "https://ostrom.test"

function bytes(length: number): Uint8Array {
    return new Uint8Array(length).fill(length)
}

/** A browser that answers with the callback the test builds from the start URL it was given. */
function browser(answer: (start: URL) => AuthSessionResult): jest.Mock<Promise<AuthSessionResult>, [string, string]> {
    return jest.fn((url: string, _redirectUri: string) => Promise.resolve(answer(new URL(url))))
}

function account(exchange: AccountRepository["exchangeCode"] = jest.fn()): AccountRepository {
    return {me: jest.fn(), exchangeCode: exchange, logout: jest.fn()}
}

const callback = (query: Record<string, string>): AuthSessionResult => ({
    type: "success",
    url: `${REDIRECT_URI}?${new URLSearchParams(query).toString()}`,
})

describe("GithubSignInImpl", () => {
    it("signs in with PKCE and a state, and exchanges the code for a session", async () => {
        const openAuthSession = browser((start) => callback({code: "good-code", state: start.searchParams.get("state") ?? ""}))
        const exchangeCode = jest.fn((_request: TokenExchangeRequest) =>
            Promise.resolve({token: "ost_1", user: {login: "rev-b"}}),
        )
        const signIn = new GithubSignInImpl({openAuthSession, randomBytes: bytes, accountOf: () => account(exchangeCode)})

        const outcome = await signIn.signIn(SERVER)

        expect(outcome).toEqual({
            kind: "signed-in",
            session: {serverUrl: SERVER, token: "ost_1", user: {login: "rev-b"}},
        })
        const [startUrl, redirect] = openAuthSession.mock.calls[0] ?? []
        const start = new URL(startUrl ?? "")
        expect(`${start.origin}${start.pathname}`).toBe(`${SERVER}/api/v1/auth/github/start`)
        expect(redirect).toBe(REDIRECT_URI)
        expect(start.searchParams.get("code_challenge_method")).toBe("S256")
        const exchange = exchangeCode.mock.calls[0]?.[0]
        expect(exchange?.code).toBe("good-code")
        expect(pkceChallenge(exchange?.codeVerifier ?? "")).toBe(start.searchParams.get("code_challenge"))
    })

    it("is cancelled when the person closes the browser", async () => {
        const signIn = new GithubSignInImpl({
            openAuthSession: browser(() => ({type: "cancel"})),
            randomBytes: bytes,
            accountOf: () => account(),
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({kind: "cancelled"})
    })

    it("refuses a callback from another sign-in", async () => {
        const signIn = new GithubSignInImpl({
            openAuthSession: browser(() => callback({code: "c", state: "forged"})),
            randomBytes: bytes,
            accountOf: () => account(),
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({kind: "failed", reason: "state-mismatch"})
    })

    it("reports a denial, with or without its state", async () => {
        const denied = new GithubSignInImpl({
            openAuthSession: browser((start) => callback({error: "access_denied", state: start.searchParams.get("state") ?? ""})),
            randomBytes: bytes,
            accountOf: () => account(),
        })
        await expect(denied.signIn(SERVER)).resolves.toEqual({kind: "failed", reason: "denied"})
        const garbled = new GithubSignInImpl({
            openAuthSession: browser(() => callback({nothing: "here"})),
            randomBytes: bytes,
            accountOf: () => account(),
        })
        await expect(garbled.signIn(SERVER)).resolves.toEqual({kind: "failed", reason: "denied"})
        const stateless = new GithubSignInImpl({
            openAuthSession: browser(() => callback({error: "access_denied"})),
            randomBytes: bytes,
            accountOf: () => account(),
        })
        await expect(stateless.signIn(SERVER)).resolves.toEqual({kind: "failed", reason: "denied"})
    })

    it("reports a code the server does not accept", async () => {
        const signIn = new GithubSignInImpl({
            openAuthSession: browser((start) => callback({code: "bad", state: start.searchParams.get("state") ?? ""})),
            randomBytes: bytes,
            accountOf: () =>
                account(() => Promise.reject(new ApiError({kind: "refused", status: 400, type: "invalid_code", message: ""}))),
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({kind: "failed", reason: "exchange-failed"})
    })
})
