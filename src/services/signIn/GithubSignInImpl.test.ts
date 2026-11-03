// SPDX-License-Identifier: AGPL-3.0-only
import type {TokenExchangeRequest} from "@/api/contract/account"
import type {MyOrganization} from "@/api/contract/organizations"
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

/** A server before `GET /me/organizations`: the route answers 404. */
const noOrganizationRoute = (): Promise<never> =>
    Promise.reject(new ApiError({kind: "refused", status: 404, type: "not_found", message: "No such route"}))

function account(
    exchange: AccountRepository["exchangeCode"] = jest.fn(),
    me: AccountRepository["me"] = jest.fn(),
    organizations: AccountRepository["organizations"] = noOrganizationRoute,
): AccountRepository {
    return {me, organizations, exchangeCode: exchange, unlock: jest.fn(), logout: jest.fn()}
}

const acme = {id: "org_acme", name: "acme", avatarUrl: "https://avatars.test/acme", connectedVia: "github-app" as const}

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

    it("keeps the organization the server acts in, read with the new token", async () => {
        const accountOf = jest.fn((_serverUrl: string, _token?: string) =>
            account(
                () => Promise.resolve({token: "ost_1", user: {login: "rev-b"}}),
                () => Promise.resolve({dev: {login: "rev-b", verified: true, via: "github"}, organization: acme, capabilities: []}),
            ),
        )
        const signIn = new GithubSignInImpl({
            openAuthSession: browser((start) => callback({code: "good-code", state: start.searchParams.get("state") ?? ""})),
            randomBytes: bytes,
            accountOf,
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({
            kind: "signed-in",
            session: {serverUrl: SERVER, token: "ost_1", user: {login: "rev-b"}, orgId: "org_acme"},
        })
        expect(accountOf).toHaveBeenCalledWith(SERVER, "ost_1")
    })

    it("gives the unlock key of the exchange with the session, never inside it", async () => {
        const signIn = new GithubSignInImpl({
            openAuthSession: browser((start) => callback({code: "good-code", state: start.searchParams.get("state") ?? ""})),
            randomBytes: bytes,
            accountOf: () => account(() => Promise.resolve({token: "ost_1", user: {login: "rev-b"}, unlockKey: "key-1"})),
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({
            kind: "signed-in",
            session: {serverUrl: SERVER, token: "ost_1", user: {login: "rev-b"}},
            unlockKey: "key-1",
        })
    })

    it("acts in the only organization the person is a member of, without asking me", async () => {
        const me = jest.fn<ReturnType<AccountRepository["me"]>, []>()
        const signIn = new GithubSignInImpl({
            openAuthSession: browser((start) => callback({code: "good-code", state: start.searchParams.get("state") ?? ""})),
            randomBytes: bytes,
            accountOf: () =>
                account(
                    () => Promise.resolve({token: "ost_1", user: {login: "rev-b"}}),
                    me,
                    () => Promise.resolve([{id: "org_globex", name: "globex"}]),
                ),
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({
            kind: "signed-in",
            session: {serverUrl: SERVER, token: "ost_1", user: {login: "rev-b"}, orgId: "org_globex"},
        })
        expect(me).not.toHaveBeenCalled()
    })

    it.each([
        ["several", [{id: "org_acme", name: "acme"}, {id: "org_globex", name: "globex"}]],
        ["none", []],
    ])("asks me which organization to act in when the person is a member of %s", async (_case: string, organizations: readonly MyOrganization[]) => {
        const signIn = new GithubSignInImpl({
            openAuthSession: browser((start) => callback({code: "good-code", state: start.searchParams.get("state") ?? ""})),
            randomBytes: bytes,
            accountOf: () =>
                account(
                    () => Promise.resolve({token: "ost_1", user: {login: "rev-b"}}),
                    () => Promise.resolve({dev: {login: "rev-b", verified: true, via: "github"}, organization: acme, capabilities: []}),
                    () => Promise.resolve(organizations),
                ),
        })
        await expect(signIn.signIn(SERVER)).resolves.toMatchObject({kind: "signed-in", session: {orgId: "org_acme"}})
    })

    it.each([
        ["names none", () => Promise.resolve({dev: {login: "rev-b", verified: true, via: "github"}, capabilities: []})],
        ["wants an organization named (several memberships)", () =>
            Promise.reject(new ApiError({kind: "refused", status: 409, type: "org_required", message: ""}))],
    ])("signs in without an organization when the server %s", async (_case, me: AccountRepository["me"]) => {
        const signIn = new GithubSignInImpl({
            openAuthSession: browser((start) => callback({code: "good-code", state: start.searchParams.get("state") ?? ""})),
            randomBytes: bytes,
            accountOf: () => account(() => Promise.resolve({token: "ost_1", user: {login: "rev-b"}}), me),
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({
            kind: "signed-in",
            session: {serverUrl: SERVER, token: "ost_1", user: {login: "rev-b"}},
        })
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

    it("connects nothing when the browser comes back to another address than the app's", async () => {
        const exchangeCode = jest.fn()
        const signIn = new GithubSignInImpl({
            openAuthSession: browser((start) => ({
                type: "success",
                url: `ostrom://request/r_1?code=c&state=${start.searchParams.get("state") ?? ""}`,
            })),
            randomBytes: bytes,
            accountOf: () => account(exchangeCode),
        })
        await expect(signIn.signIn(SERVER)).resolves.toEqual({kind: "failed", reason: "state-mismatch"})
        const unreadable = new GithubSignInImpl({
            openAuthSession: browser(() => ({type: "success", url: "not a url"})),
            randomBytes: bytes,
            accountOf: () => account(exchangeCode),
        })
        await expect(unreadable.signIn(SERVER)).resolves.toEqual({kind: "failed", reason: "state-mismatch"})
        expect(exchangeCode).not.toHaveBeenCalled()
    })

    it("lets only the last sign-in asked connect: an earlier one's return connects nothing", async () => {
        // Each sign-in draws its own state.
        let drawn = 0
        const randomBytes = (length: number): Uint8Array => new Uint8Array(length).fill((drawn += 1))
        const returns: ((result: AuthSessionResult) => void)[] = []
        const starts: URL[] = []
        const openAuthSession = jest.fn(
            (url: string, _redirectUri: string) =>
                new Promise<AuthSessionResult>((resolve) => {
                    starts.push(new URL(url))
                    returns.push(resolve)
                }),
        )
        const exchangeCode = jest.fn((_request: TokenExchangeRequest) => Promise.resolve({token: "ost_1", user: {login: "rev-b"}}))
        const signIn = new GithubSignInImpl({openAuthSession, randomBytes, accountOf: () => account(exchangeCode)})
        const stateOf = (index: number): string => starts[index]?.searchParams.get("state") ?? ""

        const first = signIn.signIn(SERVER)
        const second = signIn.signIn(SERVER)
        await Promise.resolve()
        expect(stateOf(0)).not.toBe(stateOf(1))

        // The first browser comes back with its own, valid, state: the app asked for another sign-in since.
        returns[0]?.(callback({code: "first", state: stateOf(0)}))
        await expect(first).resolves.toEqual({kind: "failed", reason: "state-mismatch"})
        returns[1]?.(callback({code: "second", state: stateOf(1)}))
        await expect(second).resolves.toMatchObject({kind: "signed-in"})
        expect(exchangeCode.mock.calls.map(([request]) => request.code)).toEqual(["second"])
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
