// SPDX-License-Identifier: AGPL-3.0-only
import {act, render, screen} from "@testing-library/react-native"
import {AppState, type AppStateStatus} from "react-native"
import {PrivacyCover} from "./PrivacyCover"

describe("PrivacyCover", () => {
    let listener: (status: AppStateStatus) => void
    let remove: jest.Mock
    let subscribe: jest.SpyInstance
    let current: jest.ReplaceProperty<AppStateStatus>

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

    it("shows nothing while the app is in front", async () => {
        await render(<PrivacyCover />)
        expect(screen.queryByTestId("privacy-cover")).toBeNull()
    })

    it.each<AppStateStatus>(["inactive", "background"])("covers the screens when the app goes %s, and uncovers them back in front", async (status) => {
        await render(<PrivacyCover />)
        await act(() => {
            listener(status)
        })
        expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
        expect(screen.getByText("Ostrom")).toBeOnTheScreen()
        await act(() => {
            listener("active")
        })
        expect(screen.queryByTestId("privacy-cover")).toBeNull()
    })

    it("covers from the start when the app opens behind another", async () => {
        current.replaceValue("background")
        await render(<PrivacyCover />)
        expect(screen.getByTestId("privacy-cover")).toBeOnTheScreen()
    })

    it("stops listening when it leaves", async () => {
        const {unmount} = await render(<PrivacyCover />)
        await act(() => {
            unmount()
        })
        expect(remove).toHaveBeenCalledTimes(1)
    })
})
