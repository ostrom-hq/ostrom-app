// SPDX-License-Identifier: AGPL-3.0-only
import {fakeAppState} from "@/test/appState"
import {onBackground} from "./onBackground"

describe("onBackground", () => {
    it("tells when the app goes to the background, never when Face ID's sheet only makes it inactive", () => {
        const appState = fakeAppState("active")
        try {
            const listener = jest.fn()
            onBackground(listener)
            appState.move("inactive")
            appState.move("active")
            expect(listener).not.toHaveBeenCalled()
            appState.move("background")
            expect(listener).toHaveBeenCalledTimes(1)
        } finally {
            appState.restore()
        }
    })
})
