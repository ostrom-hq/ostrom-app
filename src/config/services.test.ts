import * as Crypto from "expo-crypto"
import * as WebBrowser from "expo-web-browser"
import {ApprovalsRepositoryImpl} from "@/repositories/ApprovalsRepositoryImpl"
import {GithubSignInImpl} from "@/services/signIn/GithubSignInImpl"
import {createAppServices, repositoriesFor} from "./services"

// 16 zero bytes in base64url: the state the sign-in draws with the mocked random source.
const ZERO_STATE = "AAAAAAAAAAAAAAAAAAAAAA"

jest.mock("expo-web-browser", () => ({
    openAuthSessionAsync: jest.fn(() =>
        Promise.resolve({type: "success", url: "ostrom://auth/callback?code=c&state=AAAAAAAAAAAAAAAAAAAAAA"}),
    ),
}))
jest.mock("expo-crypto", () => ({getRandomBytes: jest.fn((length: number) => new Uint8Array(length))}))

describe("createAppServices", () => {
    it("wires the real implementations, down to the code exchange with the server", async () => {
        const fetch = jest.fn(() => Promise.reject(new TypeError("offline")))
        Object.defineProperty(globalThis, "fetch", {value: fetch, configurable: true, writable: true})
        const services = createAppServices()
        expect(services.signIn).toBeInstanceOf(GithubSignInImpl)
        expect(services.sessionStore.getState().state).toEqual({status: "loading"})

        const outcome = await services.signIn.signIn("https://ostrom.test")

        expect(outcome).toEqual({kind: "failed", reason: "exchange-failed"})
        expect(WebBrowser.openAuthSessionAsync).toHaveBeenCalledWith(
            expect.stringContaining(`https://ostrom.test/api/auth/github/start?redirect_uri=`),
            "ostrom://auth/callback",
        )
        expect(jest.mocked(WebBrowser.openAuthSessionAsync).mock.calls[0]?.[0]).toContain(`state=${ZERO_STATE}`)
        expect(Crypto.getRandomBytes).toHaveBeenCalledWith(32)
        expect(fetch).toHaveBeenCalledWith("https://ostrom.test/api/auth/mobile/token", expect.anything())
    })

    it("gives each session its repositories, with the session token", async () => {
        const fetch = jest.fn((_input: string, _init?: RequestInit) =>
            Promise.resolve({status: 200, ok: true, text: () => Promise.resolve(JSON.stringify({login: "rev-b"}))}),
        )
        Object.defineProperty(globalThis, "fetch", {value: fetch, configurable: true, writable: true})
        const repositories = repositoriesFor({serverUrl: "https://ostrom.test", token: "ost_1", user: {login: "rev-b"}})
        expect(repositories.approvals).toBeInstanceOf(ApprovalsRepositoryImpl)
        await expect(repositories.account.me()).resolves.toMatchObject({login: "rev-b"})
        const [url, init] = fetch.mock.calls[0] ?? []
        expect(url).toBe("https://ostrom.test/api/me")
        expect(init?.headers).toMatchObject({authorization: "Bearer ost_1"})
    })
})
