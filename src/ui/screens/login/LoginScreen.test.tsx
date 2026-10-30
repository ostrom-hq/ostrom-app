// SPDX-License-Identifier: AGPL-3.0-only
import {fireEvent, render, screen, waitFor} from "@testing-library/react-native"
import {ServicesProvider} from "@/services/AppServices"
import type {SignInOutcome} from "@/services/signIn/GithubSignIn"
import {fakeServices, testSession} from "@/test/fakeServices"
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
        ["keeps", () => Promise.resolve()],
        ["cannot keep", () => Promise.reject(new Error("no biometrics"))],
    ])("%s the unlock key of the sign-in behind the biometrics, and signs in", async (_case: string, keeping: () => Promise<void>) => {
        const keep = jest.fn((_key: string) => keeping())
        const services = fakeServices({
            signIn: {signIn: () => Promise.resolve({kind: "signed-in", session: testSession, unlockKey: "key-1"})},
            unlock: {keep, unlock: jest.fn(), forget: jest.fn()},
        })
        await renderLogin(services)
        await fireEvent.press(screen.getByRole("button", {name: "Continue with GitHub"}))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: testSession})
        })
        expect(keep).toHaveBeenCalledWith("key-1")
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
})
