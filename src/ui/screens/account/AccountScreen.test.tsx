// SPDX-License-Identifier: AGPL-3.0-only
import {fireEvent, screen, waitFor} from "@testing-library/react-native"
import {type Account, AccountSchema} from "@/api/contract/account"
import {ApiError} from "@/api/http/ApiError"
import {account} from "@/api/mocks/fixtures"
import type {AppServices} from "@/services/AppServices"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {AccountScreen} from "./AccountScreen"

const linked = AccountSchema.parse(account)

interface Harness {
    readonly services: AppServices
    readonly read: jest.Mock<Promise<Account>, []>
    readonly openUrl: jest.Mock<Promise<void>, [string]>
    readonly copyText: jest.Mock<Promise<void>, [string]>
}

async function open({
    answer = () => Promise.resolve(linked),
    opens = () => Promise.resolve(),
    copies = () => Promise.resolve(),
}: {
    answer?: () => Promise<Account>
    opens?: (url: string) => Promise<void>
    copies?: (text: string) => Promise<void>
} = {}): Promise<Harness> {
    const read = jest.fn(answer)
    const openUrl = jest.fn(opens)
    const copyText = jest.fn(copies)
    const services = await signedInServices({repositories: fakeRepositories({profile: {account: read}}), openUrl, copyText})
    await renderWithServices(<AccountScreen />, services)
    return {services, read, openUrl, copyText}
}

describe("AccountScreen", () => {
    it("shows who the person is and that their GitHub is linked", async () => {
        await open()
        expect(await screen.findByText("Reviewer B")).toBeOnTheScreen()
        expect(screen.getByText("rev-b")).toBeOnTheScreen()
        expect(screen.getByText("rev-b@acme.test")).toBeOnTheScreen()
        expect(screen.getByTestId("account-github-linked")).toHaveTextContent(/Your GitHub is linked/)
    })

    it("tells an e-mail not verified yet apart", async () => {
        await open({answer: () => Promise.resolve({...linked, emails: [...linked.emails, {email: "b@globex.test", verified: false}]})})
        expect(await screen.findByText("b@globex.test")).toBeOnTheScreen()
        expect(screen.getByText("E-mail (not verified)")).toBeOnTheScreen()
        expect(screen.getByText("E-mail")).toBeOnTheScreen()
    })

    it("says a GitHub not linked yet is linked from the console", async () => {
        await open({answer: () => Promise.resolve({...linked, providers: ["email"]})})
        expect(await screen.findByTestId("account-github-not-linked")).toHaveTextContent(/Link it from your account in the console/)
    })

    it("opens the person's account in the console", async () => {
        const {openUrl} = await open()
        await screen.findByText("Reviewer B")
        await fireEvent.press(screen.getByRole("button", {name: "Open my account in the console"}))
        await waitFor(() => {
            expect(openUrl).toHaveBeenCalledWith("https://ostrom.test/account")
        })
        expect(screen.queryByTestId("account-console-url")).toBeNull()
    })

    it("gives the address to copy when the console does not open", async () => {
        const {copyText} = await open({opens: () => Promise.reject(new Error("no browser"))})
        await fireEvent.press(await screen.findByRole("button", {name: "Open my account in the console"}))
        expect(await screen.findByTestId("account-console-url")).toHaveTextContent("https://ostrom.test/account")
        await fireEvent.press(screen.getByRole("button", {name: "Copy the address"}))
        expect(await screen.findByRole("button", {name: "Address copied"})).toBeOnTheScreen()
        expect(copyText).toHaveBeenCalledWith("https://ostrom.test/account")
    })

    it("keeps the address on screen when it cannot be copied either", async () => {
        await open({opens: () => Promise.reject(new Error("no browser")), copies: () => Promise.reject(new Error("no clipboard"))})
        await fireEvent.press(await screen.findByRole("button", {name: "Open my account in the console"}))
        await fireEvent.press(await screen.findByRole("button", {name: "Copy the address"}))
        await waitFor(() => {
            expect(screen.getByRole("button", {name: "Copy the address"})).toBeOnTheScreen()
        })
        expect(screen.getByTestId("account-console-url")).toBeOnTheScreen()
    })

    it("opens nothing once signed out", async () => {
        const {services, openUrl} = await open()
        await screen.findByText("Reviewer B")
        await services.sessionStore.getState().signOut()
        await fireEvent.press(screen.getByRole("button", {name: "Open my account in the console"}))
        expect(openUrl).not.toHaveBeenCalled()
    })

    it("says when the account could not be loaded, and reads it again", async () => {
        let fail = true
        const {read} = await open({
            answer: () => (fail ? Promise.reject(new ApiError({kind: "server", status: 500})) : Promise.resolve(linked)),
        })
        expect(await screen.findByText("Your account could not be loaded.")).toBeOnTheScreen()
        fail = false
        await fireEvent.press(screen.getByRole("button", {name: "Try again"}))
        expect(await screen.findByText("Reviewer B")).toBeOnTheScreen()
        expect(read).toHaveBeenCalledTimes(2)
    })
})
