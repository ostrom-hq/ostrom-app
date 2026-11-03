// SPDX-License-Identifier: AGPL-3.0-only
import {act, render, screen} from "@testing-library/react-native"
import {type AppStateStatus, Text} from "react-native"
import {type FakeAppState, fakeAppState} from "@/test/appState"
import {PrivacyGuard} from "./PrivacyGuard"

describe("PrivacyGuard", () => {
    let appState: FakeAppState

    beforeEach(() => {
        appState = fakeAppState("active")
    })

    afterEach(() => {
        appState.restore()
    })

    const guarded = (
        <PrivacyGuard>
            <Text>git push origin main</Text>
        </PrivacyGuard>
    )

    it("shows the screens, uncovered, while the app is in front", async () => {
        await render(guarded)
        expect(screen.getByText("git push origin main")).toBeOnTheScreen()
        expect(screen.queryByTestId("privacy-cover")).toBeNull()
    })

    it.each<AppStateStatus>(["inactive", "background"])(
        "covers the screens when the app goes %s, hides them from screen readers, and uncovers them back in front",
        async (status) => {
            await render(guarded)
            await act(() => {
                appState.move(status)
            })
            expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
            expect(screen.getByText("Ostrom")).toBeOnTheScreen()
            // Hidden from the accessibility tree: the default query no longer finds the covered text.
            expect(screen.queryByText("git push origin main")).toBeNull()
            await act(() => {
                appState.move("active")
            })
            expect(screen.queryByTestId("privacy-cover")).toBeNull()
            expect(screen.getByText("git push origin main")).toBeOnTheScreen()
        },
    )

    it("covers from the start when the app opens behind another", async () => {
        appState.set("background")
        await render(guarded)
        expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
    })

    it("reads a change made before it listens", async () => {
        // The state moves between the first render and the subscription: React reads it again once subscribed.
        appState.onSubscribe(() => {
            appState.set("background")
        })
        await render(guarded)
        expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
    })

    it("leaves the screens visible in a state that says nothing of the front", async () => {
        appState.set("unknown")
        await render(guarded)
        expect(screen.queryByTestId("privacy-cover")).toBeNull()
    })

    it("stops listening when it leaves", async () => {
        const {unmount} = await render(guarded)
        expect(appState.listening()).toBe(1)
        await unmount()
        expect(appState.listening()).toBe(0)
    })
})
