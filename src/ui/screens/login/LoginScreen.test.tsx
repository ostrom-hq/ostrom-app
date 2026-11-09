// SPDX-License-Identifier: AGPL-3.0-only
import {fireEvent, render, screen, waitFor} from "@testing-library/react-native"
import {ServicesProvider} from "@/services/AppServices"
import type {SignInOutcome} from "@/services/signIn/GithubSignIn"
import {ApiError} from "@/api/http/ApiError"
import type {Capabilities} from "@/api/contract/capabilities"
import {fakeRepositories, fakeServices, testSession} from "@/test/fakeServices"
import {LoginScreen} from "./LoginScreen"

function setup(outcome: SignInOutcome): {signIn: jest.Mock<Promise<SignInOutcome>, [string]>; services: ReturnType<typeof fakeServices>} {
    const signIn = jest.fn((_serverUrl: string) => Promise.resolve(outcome))
    const services = fakeServices({signIn: {signIn}})
    return {signIn, services}
}

async function renderLogin(services: ReturnType<typeof fakeServices>): Promise<void> {
    await render(
        <ServicesProvider services={services}>
            <LoginScreen />
        </ServicesProvider>,
    )
}

describe("LoginScreen", () => {
    it("signs in with GitHub on the default server and keeps the session", async () => {
        const {signIn, services} = setup({kind: "signed-in", session: testSession})
        await renderLogin(services)
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: testSession})
        })
        expect(signIn).toHaveBeenCalledWith("https://app.ostrom.run")
    })

    it.each([
        ["keeps", () => Promise.resolve(), 0],
        ["cannot keep", () => Promise.reject(new Error("no biometrics")), 1],
    ])("%s the unlock key of the sign-in behind the biometrics, and signs in", async (_case: string, keeping: () => Promise<void>, forgets: number) => {
        const keep = jest.fn((_key: string) => keeping())
        // An earlier key is forgotten when the new one cannot be kept, even if forgetting fails too.
        const forget = jest.fn(() => Promise.reject(new Error("keychain locked")))
        const services = fakeServices({
            signIn: {signIn: () => Promise.resolve({kind: "signed-in", session: testSession, unlockKey: "key-1"})},
            unlock: {keep, unlock: jest.fn(), forget},
        })
        await renderLogin(services)
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: testSession})
        })
        expect(keep).toHaveBeenCalledWith("key-1")
        expect(forget).toHaveBeenCalledTimes(forgets)
    })

    it("forgets an earlier unlock key when the sign-in brings none", async () => {
        const forget = jest.fn(() => Promise.resolve())
        const services = fakeServices({
            signIn: {signIn: () => Promise.resolve({kind: "signed-in", session: testSession})},
            unlock: {keep: jest.fn(), unlock: jest.fn(), forget},
        })
        await renderLogin(services)
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: testSession})
        })
        expect(forget).toHaveBeenCalledTimes(1)
    })

    it("signs in on a self-hosted server", async () => {
        const {signIn, services} = setup({kind: "cancelled"})
        await renderLogin(services)
        await fireEvent.changeText(screen.getByLabelText("Ostrom server"), "ostrom.acme.dev/")
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        await waitFor(() => {
            expect(screen.getByRole("button", {name: "Continue with GitHub"})).toBeEnabled()
        })
        expect(signIn).toHaveBeenCalledWith("https://ostrom.acme.dev")
        expect(services.sessionStore.getState().state).toEqual({status: "loading"})
    })

    it("refuses an address that is not a server, and clears the error when it is edited", async () => {
        const {signIn, services} = setup({kind: "cancelled"})
        await renderLogin(services)
        await fireEvent.changeText(screen.getByLabelText("Ostrom server"), "http://ostrom.acme.dev")
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        expect(screen.getByText("Enter the address of your Ostrom server, starting with https://.")).toBeOnTheScreen()
        expect(signIn).not.toHaveBeenCalled()
        await fireEvent.changeText(screen.getByLabelText("Ostrom server"), "https://ostrom.acme.dev")
        expect(screen.queryByText("Enter the address of your Ostrom server, starting with https://.")).toBeNull()
    })

    it("recovers when the browser or the keychain fails", async () => {
        const services = fakeServices({signIn: {signIn: () => Promise.reject(new Error("no browser"))}})
        await renderLogin(services)
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        expect(await screen.findByText("The sign-in could not finish on this phone. Try again.")).toBeOnTheScreen()
        expect(screen.getByRole("button", {name: "Continue with GitHub"})).toBeEnabled()
    })

    it("says why the sign-in failed", async () => {
        const {services} = setup({kind: "failed", reason: "exchange-failed"})
        await renderLogin(services)
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        expect(
            await screen.findByText("The server did not accept the sign-in. Check the address and try again."),
        ).toBeOnTheScreen()
    })

    describe("the server's API version", () => {
        const capabilities: Capabilities = {apiVersion: 1, serverVersion: "1.4.0", features: ["receipts.v1"]}

        function servedBy(
            read: () => Promise<Capabilities>,
            logout: () => Promise<void> = () => Promise.resolve(),
        ): {services: ReturnType<typeof fakeServices>; logout: jest.Mock<Promise<void>, []>} {
            const logoutCall = jest.fn(logout)
            const services = fakeServices({
                signIn: {signIn: () => Promise.resolve({kind: "signed-in", session: testSession, unlockKey: "key-1"})},
                repositories: fakeRepositories({
                    capabilities: {read},
                    account: {me: jest.fn(), organizations: jest.fn(), exchangeCode: jest.fn(), unlock: jest.fn(), logout: logoutCall},
                }),
            })
            return {services, logout: logoutCall}
        }

        it("keeps what a compatible server serves with the session", async () => {
            const {services} = servedBy(() => Promise.resolve(capabilities))
            await renderLogin(services)
            await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
            await waitFor(() => {
                expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: {...testSession, capabilities}})
            })
        })

        it("signs in all the same when the server cannot be asked", async () => {
            const {services} = servedBy(() => Promise.reject(new ApiError({kind: "network"})))
            await renderLogin(services)
            await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
            await waitFor(() => {
                expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: testSession})
            })
        })

        it("revokes the new session of a server that speaks another version, and says so", async () => {
            const keep = jest.fn()
            const {services, logout} = servedBy(() => Promise.resolve({...capabilities, apiVersion: 2}))
            await renderLogin({...services, unlock: {keep, unlock: jest.fn(), forget: jest.fn()}})
            await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
            expect(
                await screen.findByText(
                    "This server speaks API v2; this app speaks v1. Update the app or ask your administrator to update the server.",
                ),
            ).toBeOnTheScreen()
            expect(logout).toHaveBeenCalledTimes(1)
            expect(keep).not.toHaveBeenCalled()
            expect(services.sessionStore.getState().state).toEqual({status: "loading"})
            expect(screen.getByRole("button", {name: "Continue with GitHub"})).toBeEnabled()
        })

        it("treats a server without the route as too old, even when the revocation fails", async () => {
            const {services, logout} = servedBy(
                () => Promise.reject(new ApiError({kind: "unexpected-status", status: 404})),
                () => Promise.reject(new ApiError({kind: "network"})),
            )
            await renderLogin(services)
            await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
            expect(await screen.findByText(/This server is too old for this app/)).toBeOnTheScreen()
            expect(logout).toHaveBeenCalledTimes(1)
            expect(services.sessionStore.getState().state).toEqual({status: "loading"})
        })
    })
})
