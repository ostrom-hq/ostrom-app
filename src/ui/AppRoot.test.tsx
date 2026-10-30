import {screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ApprovalPage} from "@/api/contract/approvals"
import {ApiError} from "@/api/http/ApiError"
import {SESSION_KEY} from "@/stores/session"
import {approvals, fakeRepositories, fakeServices, MemoryStorage, testSession} from "@/test/fakeServices"
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

const EMPTY: ApprovalPage = {items: []}

async function launch(
    storage: MemoryStorage,
    waiting: () => Promise<ApprovalPage> = () => Promise.resolve(EMPTY),
    withTestClient = true,
): Promise<void> {
    const services = fakeServices({
        storage,
        repositories: fakeRepositories({approvals: {waiting, approval: jest.fn(), answer: jest.fn()}}),
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

    it("signs out when the server no longer accepts the token", async () => {
        const storage = keptSession()
        await launch(storage, () => Promise.reject(new ApiError({kind: "unauthorized"})), false)
        expect(await screen.findByRole("button", {name: "Continue with GitHub"})).toBeOnTheScreen()
        expect(storage.items.has(SESSION_KEY)).toBe(false)
    })
})

async function openUrl(url: string): Promise<void> {
    const services = fakeServices({
        storage: keptSession(),
        repositories: fakeRepositories({
            approvals: {waiting: () => Promise.resolve(EMPTY), approval: () => Promise.resolve(approvals.pending), answer: jest.fn()},
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
    it("opens a request from its deep link", async () => {
        await openUrl("/request/appr_0001")
        expect(await screen.findByRole("header", {name: "Merge PR #6 into main"})).toBeOnTheScreen()
    })

    it("refuses an id that is not one", async () => {
        await openUrl("/request/bad.id")
        expect(await screen.findByText("This request could not be loaded.")).toBeOnTheScreen()
    })
})
