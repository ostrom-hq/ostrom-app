// SPDX-License-Identifier: AGPL-3.0-only
import {act, fireEvent, screen} from "@testing-library/react-native"
import {AppState, type AppStateStatus} from "react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ApprovalQueue} from "@/api/contract/approvals"
import {ApiError} from "@/api/http/ApiError"
import type {PhoneUnlock} from "@/services/unlock/PhoneUnlock"
import {SESSION_KEY} from "@/stores/session"
import {approvals, fakeRepositories, fakeServices, MemoryStorage, testSession, viewOf} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {TabsLayout} from "@/ui/navigation/TabsLayout"
import {ActivityScreen} from "@/ui/screens/activity/ActivityScreen"
import {HistoryScreen} from "@/ui/screens/history/HistoryScreen"
import {InboxScreen} from "@/ui/screens/inbox/InboxScreen"
import {LoginScreen} from "@/ui/screens/login/LoginScreen"
import {RequestRoute} from "@/ui/screens/request/RequestRoute"
import {SettingsScreen} from "@/ui/screens/settings/SettingsScreen"
import {SpendingScreen} from "@/ui/screens/spending/SpendingScreen"
import {AppRoot} from "./AppRoot"

const EMPTY: ApprovalQueue = {operator: "rev-b", you: {login: "rev-b", via: "app"}, pending: [], recent: []}

async function launch(
    storage: MemoryStorage,
    queue: () => Promise<ApprovalQueue> = () => Promise.resolve(EMPTY),
    withTestClient = true,
    unlock?: PhoneUnlock,
): Promise<void> {
    const services = fakeServices({
        storage,
        repositories: fakeRepositories({approvals: {queue, view: jest.fn(), answer: jest.fn()}}),
        ...(unlock === undefined ? {} : {unlock}),
    })
    const root = withTestClient ? <AppRoot services={services} queryClient={testQueryClient()} /> : <AppRoot services={services} />
    await renderRouter(
        {
            _layout: (): React.JSX.Element => root,
            "(tabs)/_layout": TabsLayout,
            "(tabs)/index": InboxScreen,
            "(tabs)/history": HistoryScreen,
            "(tabs)/activity": ActivityScreen,
            "(tabs)/spending": SpendingScreen,
            "(tabs)/settings": SettingsScreen,
            login: LoginScreen,
            "request/[id]": RequestRoute,
        },
        {initialUrl: "/"},
    )
}

function keptSession(): MemoryStorage {
    const storage = new MemoryStorage()
    storage.items.set(SESSION_KEY, JSON.stringify(testSession))
    return storage
}

describe("AppRoot", () => {
    it("opens on the login screen when signed out", async () => {
        await launch(new MemoryStorage())
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
    })

    it("opens on the inbox when a session is kept", async () => {
        await launch(keptSession())
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
    })

    it("reads the inbox once at a launch with an organization kept: a launch is no change of organization", async () => {
        const storage = new MemoryStorage()
        storage.items.set(SESSION_KEY, JSON.stringify({...testSession, orgId: "org_acme"}))
        const queue = jest.fn(() => Promise.resolve(EMPTY))
        await launch(storage, queue)
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
        expect(queue).toHaveBeenCalledTimes(1)
    })

    it("reads the organization's data again through its own repositories once another is chosen, and keeps the person's", async () => {
        const base = fakeServices({storage: keptSession()})
        const services = {
            ...base,
            repositoriesFor: (session: {readonly orgId?: string | undefined}) =>
                fakeRepositories({
                    approvals: {
                        queue: () => Promise.resolve(session.orgId === "org_globex" ? {...EMPTY, pending: [viewOf(approvals.pending)]} : EMPTY),
                        view: jest.fn(),
                        answer: jest.fn(),
                    },
                }),
        }
        const client = testQueryClient()
        await renderRouter(
            {
                _layout: (): React.JSX.Element => <AppRoot services={services} queryClient={client} />,
                "(tabs)/_layout": TabsLayout,
                "(tabs)/index": InboxScreen,
                "(tabs)/history": HistoryScreen,
                "(tabs)/activity": ActivityScreen,
                "(tabs)/spending": SpendingScreen,
                "(tabs)/settings": SettingsScreen,
                login: LoginScreen,
            },
            {initialUrl: "/"},
        )
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
        client.setQueryData(["account", "organizations"], [{id: "org_globex", name: "globex"}])
        await act(async () => {
            await services.sessionStore.getState().chooseOrg("org_globex")
        })
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
        expect(client.getQueryData(["account", "organizations"])).toEqual([{id: "org_globex", name: "globex"}])
    })

    it("covers the request in the app switcher's picture: nothing of it shows while the app is not in front", async () => {
        const listeners: ((status: AppStateStatus) => void)[] = []
        const subscribe = jest.spyOn(AppState, "addEventListener").mockImplementation((_type, handler) => {
            listeners.push(handler)
            return {remove: jest.fn()}
        })
        await launch(keptSession())
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
        expect(screen.queryByTestId("privacy-cover")).toBeNull()
        await act(() => {
            for (const listener of listeners) listener("inactive")
        })
        expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
        await act(() => {
            for (const listener of listeners) listener("active")
        })
        expect(screen.queryByTestId("privacy-cover")).toBeNull()
        subscribe.mockRestore()
    })

    it("keeps the unlock key at a launch that opens signed out: the session it belongs to may still be kept", async () => {
        const storage = keptSession()
        storage.get = (): Promise<string | undefined> => Promise.reject(new Error("keychain not readable yet"))
        const forget = jest.fn(() => Promise.resolve())
        await launch(storage, undefined, true, {keep: jest.fn(), unlock: jest.fn(), forget})
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
        expect(forget).not.toHaveBeenCalled()
    })

    it("signs out when the server no longer accepts the token, and forgets the unlock key with the session", async () => {
        const storage = keptSession()
        // Even a key that cannot be deleted does not hold the sign-out.
        const forget = jest.fn(() => Promise.reject(new Error("keychain locked")))
        await launch(storage, () => Promise.reject(new ApiError({kind: "unauthorized"})), false, {keep: jest.fn(), unlock: jest.fn(), forget})
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
        expect(storage.items.has(SESSION_KEY)).toBe(false)
        expect(forget).toHaveBeenCalled()
    })
})

async function openUrl(url: string): Promise<void> {
    const services = fakeServices({
        storage: keptSession(),
        repositories: fakeRepositories({
            approvals: {queue: () => Promise.resolve(EMPTY), view: () => Promise.resolve(viewOf(approvals.pending)), answer: jest.fn()},
        }),
    })
    await renderRouter(
        {
            _layout: (): React.JSX.Element => <AppRoot services={services} queryClient={testQueryClient()} />,
            "(tabs)/_layout": TabsLayout,
            "(tabs)/index": InboxScreen,
            "(tabs)/history": HistoryScreen,
            "(tabs)/activity": ActivityScreen,
            "(tabs)/spending": SpendingScreen,
            "(tabs)/settings": SettingsScreen,
            login: LoginScreen,
            "request/[id]": RequestRoute,
        },
        {initialUrl: url},
    )
}

describe("RequestRoute", () => {
    it("leads to the choice of the organization in Settings when the request needs it named", async () => {
        const services = fakeServices({
            storage: keptSession(),
            repositories: fakeRepositories({
                approvals: {
                    queue: () => Promise.resolve(EMPTY),
                    view: () => Promise.reject(new ApiError({kind: "refused", status: 409, type: "org_required", message: ""})),
                    answer: jest.fn(),
                },
            }),
        })
        const rendered = await renderRouter(
            {
                _layout: (): React.JSX.Element => <AppRoot services={services} queryClient={testQueryClient()} />,
                "(tabs)/_layout": TabsLayout,
                "(tabs)/index": InboxScreen,
                "(tabs)/history": HistoryScreen,
                "(tabs)/activity": ActivityScreen,
                "(tabs)/spending": SpendingScreen,
                "(tabs)/settings": (): React.JSX.Element => <></>,
                login: LoginScreen,
                "request/[id]": RequestRoute,
            },
            {initialUrl: "/request/appr_0001"},
        )
        await fireEvent.press(await screen.findByRole("button", {name: "Choose the organization"}))
        expect(rendered.getPathname()).toBe("/settings")
    })

    it("opens a request from its deep link", async () => {
        await openUrl("/request/appr_0001")
        expect(await screen.findByRole("header", {name: "Merge PR #6 into main"})).toBeOnTheScreen()
    })

    it("refuses an id that is not one", async () => {
        await openUrl("/request/bad.id")
        expect(await screen.findByText("This request could not be loaded.")).toBeOnTheScreen()
    })
})
