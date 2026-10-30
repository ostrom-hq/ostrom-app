// SPDX-License-Identifier: AGPL-3.0-only
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

interface Launched {
    readonly router: Awaited<ReturnType<typeof renderRouter>>
    readonly tap: (data: unknown) => void
    readonly unsubscribe: jest.Mock
}

async function launch(launchData: unknown, read: () => Promise<unknown> = () => Promise.resolve(launchData)): Promise<Launched> {
    let tap: (data: unknown) => void = () => undefined
    const unsubscribe = jest.fn()
    const services = await signedInServices({
        push: {
            register: () => Promise.resolve({kind: "unavailable"}),
            onOpen: (listener) => {
                tap = listener
                return unsubscribe
            },
            launchData: read,
        },
    })
    const client = testQueryClient()
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    const router = await renderRouter({index: Home, "request/[id]": AskStub}, {initialUrl: "/", wrapper})
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
        const {router} = await launch({kind: "approval", approvalId: "appr_0001"})
        expect(await screen.findByText("request")).toBeOnTheScreen()
        expect(router.getPathname()).toBe("/request/appr_0001")
    })

    it("opens nothing when the launch notification cannot be read", async () => {
        const {router} = await launch(undefined, () => Promise.reject(new Error("no module")))
        await act(async () => {
            await Promise.resolve()
        })
        expect(router.getPathname()).toBe("/")
    })

    it("opens nothing when the launch notification is read after the screen left", async () => {
        let answer: (data: unknown) => void = () => undefined
        const {router, unsubscribe} = await launch(
            undefined,
            () =>
                new Promise((resolve) => {
                    answer = resolve
                }),
        )
        await router.unmount()
        expect(unsubscribe).toHaveBeenCalledTimes(1)
        await act(async () => {
            answer({kind: "approval", approvalId: "appr_0001"})
            await Promise.resolve()
        })
        expect(screen.queryByText("request")).toBeNull()
    })

    it("opens the request of a tapped notification, and ignores anything else", async () => {
        const {router, tap} = await launch(undefined)
        await act(async () => {
            tap({kind: "marketing", url: "https://example.com"})
            await Promise.resolve()
        })
        expect(router.getPathname()).toBe("/")
        await act(async () => {
            tap({kind: "approval", approvalId: "../settings"})
            await Promise.resolve()
        })
        expect(router.getPathname()).toBe("/")
        await act(async () => {
            tap({kind: "approval", approvalId: "appr_0002"})
            await Promise.resolve()
        })
        expect(await screen.findByText("request")).toBeOnTheScreen()
        expect(router.getPathname()).toBe("/request/appr_0002")
    })
})
