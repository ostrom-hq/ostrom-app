// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {fireEvent, screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import {Text} from "react-native"
import {type AppServices, ServicesProvider} from "@/services/AppServices"
import {signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {TabsLayout} from "./TabsLayout"

async function launch(extra: Partial<AppServices>): Promise<void> {
    const services = {...(await signedInServices()), ...extra}
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
})
