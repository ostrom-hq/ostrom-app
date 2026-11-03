// SPDX-License-Identifier: AGPL-3.0-only
import * as Clipboard from "expo-clipboard"
import * as Crypto from "expo-crypto"
import * as Linking from "expo-linking"
import * as WebBrowser from "expo-web-browser"
import {ApprovalsRepositoryImpl} from "@/repositories/ApprovalsRepositoryImpl"
import {PinnedReceiptsRepository} from "@/repositories/PinnedReceiptsRepository"
import {GithubSignInImpl} from "@/services/signIn/GithubSignInImpl"
import {SecureStorageImpl} from "@/services/storage/SecureStorageImpl"
import {SESSION_KEY} from "@/stores/session"
import {servicesForBuild} from "./buildServices"
import {servicesForBuild as e2eServicesForBuild} from "./buildServicesE2e"
import {servicesForBuild as realServicesForBuild} from "./buildServicesReal"
import {createMockAppServices} from "./mockServices"
import {createRealServerAppServices, realServer} from "./realServices"
import {createAppServices, easProjectId, keepsServer, repositoriesFor} from "./services"

// 16 zero bytes in base64url: the state the sign-in draws with the mocked random source.
const ZERO_STATE = "AAAAAAAAAAAAAAAAAAAAAA"

jest.mock("expo-web-browser", () => ({
    openAuthSessionAsync: jest.fn(() =>
        Promise.resolve({type: "success", url: "ostrom://auth/callback?code=c&state=AAAAAAAAAAAAAAAAAAAAAA"}),
    ),
}))
jest.mock("expo-notifications", () => ({setNotificationHandler: jest.fn()}))
jest.mock("expo-linking", () => ({openURL: jest.fn(() => Promise.resolve(true))}))
jest.mock("expo-clipboard", () => ({setStringAsync: jest.fn(() => Promise.resolve(true))}))
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
            expect.stringContaining(`https://ostrom.test/api/v1/auth/github/start?redirect_uri=`),
            "ostrom://auth/callback",
        )
        expect(jest.mocked(WebBrowser.openAuthSessionAsync).mock.calls[0]?.[0]).toContain(`state=${ZERO_STATE}`)
        expect(Crypto.getRandomBytes).toHaveBeenCalledWith(32)
        expect(fetch).toHaveBeenCalledWith("https://ostrom.test/api/v1/auth/mobile/token", expect.anything())
    })

    it("opens a link of the console outside the app, as a universal link", async () => {
        await expect(createAppServices().openUrl("https://ostrom.test/yes/appr_1")).resolves.toBeUndefined()
        expect(Linking.openURL).toHaveBeenCalledWith("https://ostrom.test/yes/appr_1")
    })

    it("copies text to the system clipboard", async () => {
        await expect(createAppServices().copyText("https://ostrom.test/yes/appr_1")).resolves.toBeUndefined()
        expect(Clipboard.setStringAsync).toHaveBeenCalledWith("https://ostrom.test/yes/appr_1")
    })

    it("gives each session its repositories, with the session token and organization", async () => {
        const fetch = jest.fn((_input: string, _init?: RequestInit) =>
            Promise.resolve({status: 200, ok: true, text: () => Promise.resolve(JSON.stringify({dev: {login: "rev-b", verified: true, via: "github"}, capabilities: []}))}),
        )
        Object.defineProperty(globalThis, "fetch", {value: fetch, configurable: true, writable: true})
        const repositories = repositoriesFor({serverUrl: "https://ostrom.test", token: "ost_1", user: {login: "rev-b"}, orgId: "org_acme"})
        expect(repositories.approvals).toBeInstanceOf(ApprovalsRepositoryImpl)
        // The receipt keys go through the keys this phone kept for the server (AUDIT-SECU-APP-2 A5).
        expect(repositories.receipts).toBeInstanceOf(PinnedReceiptsRepository)
        await expect(repositories.account.me()).resolves.toMatchObject({dev: {login: "rev-b"}})
        const [url, init] = fetch.mock.calls[0] ?? []
        expect(url).toBe("https://ostrom.test/api/v1/auth/me")
        expect(init?.headers).toMatchObject({authorization: "Bearer ost_1", "ostrom-org": "org_acme"})
    })
})

describe("keepsServer", () => {
    it("keeps an HTTPS server in any build, and HTTP on this computer only in a development build", () => {
        expect(keepsServer("https://ostrom.acme.dev", false)).toBe(true)
        expect(keepsServer("http://10.0.2.2:4961", false)).toBe(false)
        expect(keepsServer("http://localhost:4961", false)).toBe(false)
        expect(keepsServer("http://10.0.2.2:4961", true)).toBe(true)
        expect(keepsServer("http://ostrom.acme.dev", true)).toBe(false)
    })
})

describe("the session kept at launch", () => {
    const spies: {mockRestore: () => void}[] = []

    /** A session kept in the keychain by an earlier build, on the given server; its removal is spied on. */
    function keptOn(serverUrl: string): jest.SpiedFunction<SecureStorageImpl["remove"]> {
        for (const spy of spies.splice(0)) spy.mockRestore()
        const stored = JSON.stringify({serverUrl, token: "ost_1", user: {login: "rev-b"}})
        spies.push(jest.spyOn(SecureStorageImpl.prototype, "get").mockImplementation((key) => Promise.resolve(key === SESSION_KEY ? stored : undefined)))
        const remove = jest.spyOn(SecureStorageImpl.prototype, "remove").mockResolvedValue()
        spies.push(remove)
        return remove
    }

    beforeEach(() => {
        // A store build.
        Reflect.set(globalThis, "__DEV__", false)
    })

    afterEach(() => {
        Reflect.set(globalThis, "__DEV__", true)
        for (const spy of spies.splice(0)) spy.mockRestore()
    })

    it("is dropped by a store build when its server is not HTTPS: an older development build kept it", async () => {
        const remove = keptOn("http://10.0.2.2:4961")
        const services = createAppServices()
        await services.sessionStore.getState().hydrate()
        expect(services.sessionStore.getState().state).toEqual({status: "signed-out"})
        expect(remove).toHaveBeenCalledWith(SESSION_KEY)
    })

    it("is kept by a store build on an HTTPS server", async () => {
        keptOn("https://ostrom.acme.dev")
        const services = createAppServices()
        await services.sessionStore.getState().hydrate()
        expect(services.sessionStore.getState().state).toMatchObject({status: "signed-in"})
    })

    it("is kept by the real-server E2E build on its own server only", async () => {
        const server = realServer({serverUrl: "http://10.0.2.2:4600", token: "osm_ci", login: "e2e", unlockKey: "key_ci"})
        keptOn("http://10.0.2.2:4600")
        const own = createRealServerAppServices(server)
        await own.sessionStore.getState().hydrate()
        expect(own.sessionStore.getState().state).toMatchObject({status: "signed-in"})
        keptOn("http://10.0.2.2:4961")
        const other = createRealServerAppServices(server)
        await other.sessionStore.getState().hydrate()
        expect(other.sessionStore.getState().state).toEqual({status: "signed-out"})
    })
})

describe("easProjectId", () => {
    it("reads the EAS project id, and nothing before the project exists", () => {
        expect(easProjectId({eas: {projectId: "p-1"}})).toBe("p-1")
        expect(easProjectId(undefined)).toBeUndefined()
        expect(easProjectId({eas: {}})).toBeUndefined()
    })
})

describe("createMockAppServices", () => {
    it("signs in on the address typed, without GitHub, and talks to the in-memory server", async () => {
        const services = createMockAppServices()
        const outcome = await services.signIn.signIn("https://ostrom.e2e")
        expect(outcome).toEqual({
            kind: "signed-in",
            session: {serverUrl: "https://ostrom.e2e", token: "ost_mock_token", user: {login: "rev-b"}, orgId: "org_acme"},
            unlockKey: "mock-unlock-key",
        })
        if (outcome.kind !== "signed-in") throw new Error("not signed in")
        const repositories = services.repositoriesFor(outcome.session)
        const queue = await repositories.approvals.queue()
        expect(queue.pending.map((view) => view.approval.id)).toContain("appr_0004")
        // The key of the sign-in unlocks against the in-memory server, as against the real one.
        await services.unlock.keep(outcome.unlockKey ?? "")
        expect(await services.unlock.unlock("Approve", repositories.account, {always: true})).toBe("confirmed")
    })

    it("confirms without a finger and has no push, as on the emulator", async () => {
        const services = createMockAppServices()
        expect(await services.biometrics.confirm("Approve")).toBe("confirmed")
        expect(await services.push.register()).toEqual({kind: "unavailable"})
        expect(await services.push.launchData()).toBeUndefined()
        const unsubscribe = services.push.onOpen(jest.fn())
        expect(unsubscribe).not.toThrow()
    })
})

describe("createRealServerAppServices", () => {
    it("signs in with the token the CI made on its server, whatever address is typed", async () => {
        const services = createRealServerAppServices(
            realServer({serverUrl: "http://10.0.2.2:4600", token: "osm_ci", login: "e2e", unlockKey: "key_ci"}),
        )
        expect(await services.signIn.signIn("https://app.ostrom.run")).toEqual({
            kind: "signed-in",
            session: {serverUrl: "http://10.0.2.2:4600", token: "osm_ci", user: {login: "e2e"}},
            unlockKey: "key_ci",
        })
        expect(services.repositoriesFor).toBe(repositoriesFor)
        expect(await services.biometrics.confirm("Approve")).toBe("confirmed")
        expect(await services.push.register()).toEqual({kind: "unavailable"})
        expect(await services.push.launchData()).toBeUndefined()
        const unsubscribe = services.push.onOpen(jest.fn())
        expect(unsubscribe).not.toThrow()
    })

    it("stops at once when the build was not given its server, token and login", () => {
        expect(() => realServer({token: "osm_ci", login: "e2e", unlockKey: "key_ci"})).toThrow(
            /needs EXPO_PUBLIC_OSTROM_E2E_SERVER$/,
        )
        expect(() =>
            realServer({serverUrl: "http://10.0.2.2:4600", token: "", login: "e2e", unlockKey: "key_ci"}),
        ).toThrow(/needs EXPO_PUBLIC_OSTROM_E2E_TOKEN$/)
        expect(() => realServer({serverUrl: "not a url", token: "osm_ci", unlockKey: "key_ci"})).toThrow(
            /needs EXPO_PUBLIC_OSTROM_E2E_SERVER, EXPO_PUBLIC_OSTROM_E2E_LOGIN$/,
        )
        expect(() => realServer({serverUrl: "http://10.0.2.2:4600", token: "osm_ci", login: "e2e"})).toThrow(
            /needs EXPO_PUBLIC_OSTROM_E2E_UNLOCK_KEY$/,
        )
        // Nothing at all: the four are named.
        expect(() => realServer(undefined)).toThrow(/needs EXPO_PUBLIC_OSTROM_E2E_SERVER, _TOKEN, _LOGIN and _UNLOCK_KEY$/)
    })
})

describe("realServicesForBuild", () => {
    it("stops at once when the build was given none of its variables", () => {
        // Nothing of the kind in the test environment: the job sets them for its build only.
        expect(() => realServicesForBuild()).toThrow(
            /needs EXPO_PUBLIC_OSTROM_E2E_SERVER, EXPO_PUBLIC_OSTROM_E2E_TOKEN, EXPO_PUBLIC_OSTROM_E2E_LOGIN, EXPO_PUBLIC_OSTROM_E2E_UNLOCK_KEY$/,
        )
    })
})

describe("servicesForBuild", () => {
    it("gives the real services, or the in-memory server's for the E2E build", async () => {
        expect(servicesForBuild().signIn).toBeInstanceOf(GithubSignInImpl)
        expect(await e2eServicesForBuild().signIn.signIn("https://ostrom.e2e")).toMatchObject({kind: "signed-in"})
    })
})
