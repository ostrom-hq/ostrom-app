// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {fireEvent, screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import {Text} from "react-native"
import {type Wait, WaitSchema} from "@/api/contract/waits"
import {confirmWait} from "@/api/mocks/fixtures"
import {type AppServices, ServicesProvider} from "@/services/AppServices"
import {fakeRepositories, signedInServices, testSession} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {TabsLayout} from "./TabsLayout"

async function launch(extra: Partial<AppServices>, features?: readonly string[], waitsOpen: readonly Wait[] = []): Promise<void> {
    const repositories = fakeRepositories({waits: {list: () => Promise.resolve({items: [...waitsOpen]}), answer: jest.fn()}})
    const services = {...(await signedInServices({repositories})), ...extra}
    if (features !== undefined) {
        await services.sessionStore.getState().signIn({...testSession, capabilities: {apiVersion: 1, serverVersion: "1.4.0", features: [...features]}})
    }
    const client = testQueryClient()
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    const screenOf = (name: string) => (): React.JSX.Element => <Text>{`${name} screen`}</Text>
    await renderRouter(
        {
            "(tabs)/_layout": TabsLayout,
            "(tabs)/index": screenOf("inbox"),
            "(tabs)/waits": screenOf("waits"),
            "(tabs)/history": screenOf("history"),
            "(tabs)/activity": screenOf("activity"),
            "(tabs)/spending": screenOf("spending"),
            "(tabs)/settings": screenOf("settings"),
        },
        {initialUrl: "/", wrapper},
    )
}

describe("TabsLayout", () => {
    it("tells the services which tab is pressed, for the performance pass", async () => {
        const onTabPress = jest.fn()
        await launch({onTabPress})
        await fireEvent.press(await screen.findByText("Activity"))
        expect(await screen.findByText("activity screen")).toBeOnTheScreen()
        expect(onTabPress).toHaveBeenCalledWith("activity")
        await fireEvent.press(screen.getByText("History"))
        expect(onTabPress).toHaveBeenLastCalledWith("history")
    })

    it("presses a tab without the services listening", async () => {
        await launch({})
        await fireEvent.press(await screen.findByText("Activity"))
        expect(await screen.findByText("activity screen")).toBeOnTheScreen()
    })

    it("shows every tab while the features of the server are not known", async () => {
        await launch({})
        expect(await screen.findByText("History")).toBeOnTheScreen()
        expect(screen.getByText("Spending")).toBeOnTheScreen()
    })

    it("hides the tabs of the functions the server does not serve", async () => {
        await launch({}, ["gates.v1"])
        expect(await screen.findByText("Activity")).toBeOnTheScreen()
        expect(screen.queryByText("Waits")).toBeNull()
        expect(screen.queryByText("History")).toBeNull()
        expect(screen.queryByText("Spending")).toBeNull()
    })

    it("keeps the tabs of the functions the server serves", async () => {
        await launch({}, ["receipts.v1", "budgets.v1"])
        expect(await screen.findByText("History")).toBeOnTheScreen()
        expect(screen.getByText("Spending")).toBeOnTheScreen()
    })

    it("shows the waits tab with the count of what is asked of the person", async () => {
        await launch({}, ["waits.v1"], [WaitSchema.parse(confirmWait)])
        expect(await screen.findByText("Waits")).toBeOnTheScreen()
        expect(await screen.findByText("1")).toBeOnTheScreen()
    })

    it("shows the waits tab without a count when nothing is asked", async () => {
        await launch({}, ["waits.v1"])
        expect(await screen.findByText("Waits")).toBeOnTheScreen()
        expect(screen.queryByText("1")).toBeNull()
    })
})
