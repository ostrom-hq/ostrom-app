import {screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {YesQueue} from "@/api/contract/yes"
import {ApiError} from "@/api/http/ApiError"
import {SESSION_KEY} from "@/stores/session"
import {asks, fakeRepositories, fakeServices, MemoryStorage, testSession} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {AskRoute} from "@/ui/screens/ask/AskRoute"
import {InboxScreen} from "@/ui/screens/inbox/InboxScreen"
import {LoginScreen} from "@/ui/screens/login/LoginScreen"
import {AppRoot} from "./AppRoot"

const EMPTY: YesQueue = {operator: "rev-b", pending: [], recent: []}

async function launch(
    storage: MemoryStorage,
    queue: () => Promise<YesQueue> = () => Promise.resolve(EMPTY),
    withTestClient = true,
): Promise<void> {
    const services = fakeServices({
        storage,
        repositories: fakeRepositories({approvals: {queue, ask: jest.fn(), decide: jest.fn()}}),
    })
    const root = withTestClient ? <AppRoot services={services} queryClient={testQueryClient()} /> : <AppRoot services={services} />
    await renderRouter(
        {_layout: (): React.JSX.Element => root, index: InboxScreen, login: LoginScreen, "ask/[id]": AskRoute},
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
            approvals: {queue: () => Promise.resolve(EMPTY), ask: () => Promise.resolve(asks.pending), decide: jest.fn()},
        }),
    })
    await renderRouter(
        {
            _layout: (): React.JSX.Element => <AppRoot services={services} queryClient={testQueryClient()} />,
            index: InboxScreen,
            login: LoginScreen,
            "ask/[id]": AskRoute,
        },
        {initialUrl: url},
    )
}

describe("AskRoute", () => {
    it("opens a request from its deep link", async () => {
        await openUrl("/ask/appr_0001")
        expect(await screen.findByRole("header", {name: "Merge PR #6 into main"})).toBeOnTheScreen()
    })

    it("refuses an id that is not one", async () => {
        await openUrl("/ask/bad.id")
        expect(await screen.findByText("This request could not be loaded.")).toBeOnTheScreen()
    })
})
