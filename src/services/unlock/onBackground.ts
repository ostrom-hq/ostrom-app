// SPDX-License-Identifier: AGPL-3.0-only
import {AppState} from "react-native"

/**
 * Calls the listener each time the app goes to the background, for as long as the app runs. Never for `inactive`:
 * Face ID's sheet makes the app so in the middle of an unlock.
 */
export function onBackground(listener: () => void): void {
    AppState.addEventListener("change", (status) => {
        if (status === "background") listener()
    })
}
