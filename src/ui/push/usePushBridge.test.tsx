import {QueryClientProvider} from "@tanstack/react-query"
import {act, screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import {Text} from "react-native"
import {ServicesProvider} from "@/services/AppServices"
import {signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {usePushBridge} from "./usePushBridge"

function Home(): React.JSX.Element {
    usePushBridge()
    return <Text>home</Text>
}

function AskStub(): React.JSX.Element {
    return <Text>request</Text>
}

async function launch(launchData: unknown): Promise<{router: ReturnType<typeof renderRouter>; tap: (data: unknown) => void; unsubscribe: jest.Mock}> {
    let tap: (data: unknown) => void = () => undefined
    const unsubscribe = jest.fn()
    const services = await signedInServices({
        push: {
            register: () => Promise.resolve({kind: "unavailable"}),
            onOpen: (listener) => {
                tap = listener
                return unsubscribe
            },
            launchData: () => Promise.resolve(launchData),
        },
    })
    const client = testQueryClient()
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    const router = await renderRouter({index: Home, "ask/[id]": AskStub}, {initialUrl: "/", wrapper})
    return {
        router,
        tap: (data) => {
            tap(data)
        },
        unsubscribe,
    }
}

describe("usePushBridge", () => {
    it("opens the request of the notification that launched the app", async () => {
        const {router} = await launch({kind: "ask", askId: "appr_0001"})
        expect(await screen.findByText("request")).toBeOnTheScreen()
        expect(router.getPathname()).toBe("/ask/appr_0001")
    })

    it("opens the request of a tapped notification, and ignores anything else", async () => {
        const {router, tap} = await launch(undefined)
        await act(async () => {
            tap({kind: "marketing", url: "https://example.com"})
            await Promise.resolve()
        })
        expect(router.getPathname()).toBe("/")
        await act(async () => {
            tap({kind: "ask", askId: "../settings"})
            await Promise.resolve()
        })
        expect(router.getPathname()).toBe("/")
        await act(async () => {
            tap({kind: "ask", askId: "appr_0002"})
            await Promise.resolve()
        })
        expect(await screen.findByText("request")).toBeOnTheScreen()
        expect(router.getPathname()).toBe("/ask/appr_0002")
    })
})
