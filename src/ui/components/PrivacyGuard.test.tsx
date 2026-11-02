// SPDX-License-Identifier: AGPL-3.0-only
import {act, render, screen} from "@testing-library/react-native"
import {AppState, type AppStateStatus, Text} from "react-native"
import {PrivacyGuard} from "./PrivacyGuard"

describe("PrivacyGuard", () => {
    let listener: (status: AppStateStatus) => void
    let remove: jest.Mock
    let subscribe: jest.SpyInstance
    let current: jest.ReplaceProperty<typeof AppState.currentState>

    beforeEach(() => {
        listener = () => undefined
        remove = jest.fn()
        subscribe = jest.spyOn(AppState, "addEventListener").mockImplementation((_type, handler) => {
            listener = handler
            return {remove}
        })
        current = jest.replaceProperty(AppState, "currentState", "active")
    })

    afterEach(() => {
        subscribe.mockRestore()
        current.restore()
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
                listener(status)
            })
            expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
            expect(screen.getByText("Ostrom")).toBeOnTheScreen()
            // Hidden from the accessibility tree: the default query no longer finds the covered text.
            expect(screen.queryByText("git push origin main")).toBeNull()
            await act(() => {
                listener("active")
            })
            expect(screen.queryByTestId("privacy-cover")).toBeNull()
            expect(screen.getByText("git push origin main")).toBeOnTheScreen()
        },
    )

    it("covers from the start when the app opens behind another", async () => {
        current.replaceValue("background")
        await render(guarded)
        expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
    })

    it("reads a change made before it listens", async () => {
        // The state moves between the first render and the subscription: the subscription reads it again.
        subscribe.mockImplementation((_type, handler) => {
            listener = handler
            current.replaceValue("background")
            return {remove}
        })
        await render(guarded)
        expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
    })

    it("leaves the screens visible in a state that says nothing of the front", async () => {
        current.replaceValue("unknown")
        await render(guarded)
        expect(screen.queryByTestId("privacy-cover")).toBeNull()
    })

    it("stops listening when it leaves", async () => {
        const {unmount} = await render(guarded)
        await act(() => {
            unmount()
        })
        expect(remove).toHaveBeenCalledTimes(1)
    })
})
