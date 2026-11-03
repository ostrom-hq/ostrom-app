// SPDX-License-Identifier: AGPL-3.0-only
import {AppState, type AppStateStatus} from "react-native"

/** The app's state as a test sets it, then puts back what React Native's Jest setup had. */
export interface FakeAppState {
    set: (status: AppStateStatus) => void
    restore: () => void
}

/**
 * Sets `AppState.currentState`. React Native's Jest setup mocks it with a function, which `jest.replaceProperty`
 * refuses to replace: the property is redefined, and its descriptor put back at the end.
 */
export function fakeAppState(status: AppStateStatus): FakeAppState {
    const original = Object.getOwnPropertyDescriptor(AppState, "currentState")
    const set = (next: AppStateStatus): void => {
        Object.defineProperty(AppState, "currentState", {configurable: true, writable: true, value: next})
    }
    set(status)
    return {
        set,
        restore: () => {
            if (original === undefined) Reflect.deleteProperty(AppState, "currentState")
            else Object.defineProperty(AppState, "currentState", original)
        },
    }
}
