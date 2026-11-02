// SPDX-License-Identifier: AGPL-3.0-only
import {act, fireEvent, screen, waitFor} from "@testing-library/react-native"
import type {MyOrganization} from "@/api/contract/organizations"
import {ApiError} from "@/api/http/ApiError"
import type {AppServices} from "@/services/AppServices"
import {fakeRepositories, MemoryStorage, signedInServices, testSession} from "@/test/fakeServices"
import {renderWithServices, testQueryClient} from "@/test/renderWithServices"
import {OrganizationSection} from "./OrganizationSection"

const acme: MyOrganization = {id: "org_acme", name: "acme"}
const globex: MyOrganization = {id: "org_globex", name: "globex"}

async function open(
    organizations: () => Promise<readonly MyOrganization[]>,
    storage = new MemoryStorage(),
): Promise<{services: AppServices; client: ReturnType<typeof testQueryClient>}> {
    const services = await signedInServices({
        storage,
        repositories: fakeRepositories({
            account: {me: jest.fn(), organizations, exchangeCode: jest.fn(), unlock: jest.fn(), logout: jest.fn()},
        }),
    })
    const client = testQueryClient()
    await renderWithServices(<OrganizationSection />, services, client)
    return {services, client}
}

function orgIdOf(services: AppServices): string | undefined {
    const {state} = services.sessionStore.getState()
    return state.status === "signed-in" ? state.session.orgId : undefined
}

describe("OrganizationSection", () => {
    it("shows the one organization the person is a member of, with nothing to choose, and acts in it", async () => {
        // The session names no organization, or one the person has left: the only membership is the one.
        const {services} = await open(() => Promise.resolve([acme]))
        expect(await screen.findByText("acme")).toBeOnTheScreen()
        expect(screen.queryByRole("radio")).toBeNull()
        await waitFor(() => {
            expect(orgIdOf(services)).toBe("org_acme")
        })
    })

    it("keeps showing the one organization when its choice cannot be saved", async () => {
        const storage = new MemoryStorage()
        const services = await signedInServices({
            storage,
            repositories: fakeRepositories({
                account: {me: jest.fn(), organizations: () => Promise.resolve([acme]), exchangeCode: jest.fn(), unlock: jest.fn(), logout: jest.fn()},
            }),
        })
        const set = jest.fn((_key: string, _value: string) => Promise.reject(new Error("keychain locked")))
        storage.set = set
        await renderWithServices(<OrganizationSection />, services, testQueryClient())
        expect(await screen.findByText("acme")).toBeOnTheScreen()
        await waitFor(() => {
            expect(set).toHaveBeenCalled()
        })
        expect(orgIdOf(services)).toBeUndefined()
        expect(screen.queryByText(/could not be/)).toBeNull()
    })

    it("says when the person is a member of no organization", async () => {
        await open(() => Promise.resolve([]))
        expect(await screen.findByText("You are not a member of any organization yet.")).toBeOnTheScreen()
    })

    it("offers to try again when the organizations cannot be loaded", async () => {
        const organizations = jest
            .fn<Promise<readonly MyOrganization[]>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue([acme])
        await open(organizations)
        expect(await screen.findByText("Your organizations could not be loaded.")).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Try again"}))
        expect(await screen.findByText("acme")).toBeOnTheScreen()
    })

    it("lets a member of several choose the one Ostrom acts in", async () => {
        const {services, client} = await open(() => Promise.resolve([acme, globex]))
        expect(await screen.findByText("You are a member of several organizations: choose the one Ostrom acts in.")).toBeOnTheScreen()
        expect(screen.getByRole("radio", {name: "acme"})).not.toBeChecked()
        await fireEvent.press(screen.getByRole("radio", {name: "globex"}))
        expect(await screen.findByText(/Ostrom acts in the organization chosen here/)).toBeOnTheScreen()
        expect(screen.getByRole("radio", {name: "globex"})).toBeChecked()
        expect(orgIdOf(services)).toBe("org_globex")
        expect(client.getQueryData(["account", "organizations"])).toEqual([acme, globex])
        // The organization already chosen: nothing to do.
        await fireEvent.press(screen.getByRole("radio", {name: "globex"}))
        expect(orgIdOf(services)).toBe("org_globex")
    })

    it("takes no second choice while the first is saved", async () => {
        const storage = new MemoryStorage()
        const {services} = await open(() => Promise.resolve([acme, globex]), storage)
        let save = (): void => undefined
        storage.set = (key: string, value: string): Promise<void> =>
            new Promise((resolve) => {
                save = () => {
                    storage.items.set(key, value)
                    resolve()
                }
            })
        await fireEvent.press(await screen.findByRole("radio", {name: "globex"}))
        expect(screen.getByRole("radio", {name: "acme"})).toBeDisabled()
        await act(() => {
            save()
        })
        await waitFor(() => {
            expect(screen.getByRole("radio", {name: "acme"})).toBeEnabled()
        })
        expect(orgIdOf(services)).toBe("org_globex")
        expect(storage.items.get("ostrom.session")).toBe(JSON.stringify({...testSession, orgId: "org_globex"}))
    })

    it("says so when the choice cannot be saved, and keeps the organization acted in", async () => {
        const storage = new MemoryStorage()
        const {services} = await open(() => Promise.resolve([acme, globex]), storage)
        storage.set = (): Promise<void> => Promise.reject(new Error("locked"))
        await fireEvent.press(await screen.findByRole("radio", {name: "globex"}))
        expect(await screen.findByRole("alert")).toHaveTextContent("The organization could not be changed. Try again.")
        expect(orgIdOf(services)).toBeUndefined()
        expect(screen.getByRole("radio", {name: "globex"})).not.toBeChecked()
    })
})
