// SPDX-License-Identifier: AGPL-3.0-only
import {AppState, type AppStateStatus} from "react-native"

/** The app's state as a test drives it, then put back as React Native's Jest setup had it. */
export interface FakeAppState {
    /** Sets the state without telling anyone: a change the app has not heard of yet. */
    set: (status: AppStateStatus) => void
    /** The app moves to a state, as React Native says it: the state, then the event to every listener. */
    move: (status: AppStateStatus) => void
    /** How many listeners are subscribed. */
    listening: () => number
    /** Called while subscribing, before the subscription is kept. */
    onSubscribe: (callback: () => void) => void
    restore: () => void
}

/**
 * Drives `AppState`. React Native's Jest setup mocks `currentState` and `addEventListener` with functions, which
 * `jest.replaceProperty` refuses and `mockRestore` would empty for the rest of the file: both properties are redefined,
 * and their descriptors put back at the end.
 */
export function fakeAppState(status: AppStateStatus): FakeAppState {
    const originalState = Object.getOwnPropertyDescriptor(AppState, "currentState")
    const originalListen = Object.getOwnPropertyDescriptor(AppState, "addEventListener")
    const listeners = new Set<(status: AppStateStatus) => void>()
    let subscribing = (): void => undefined
    const set = (next: AppStateStatus): void => {
        Object.defineProperty(AppState, "currentState", {configurable: true, writable: true, value: next})
    }
    const listen = (_type: string, listener: (status: AppStateStatus) => void): {remove: () => void} => {
        subscribing()
        listeners.add(listener)
        return {
            remove: () => {
                listeners.delete(listener)
            },
        }
    }
    const put = (name: "currentState" | "addEventListener", descriptor: PropertyDescriptor | undefined): void => {
        if (descriptor === undefined) Reflect.deleteProperty(AppState, name)
        else Object.defineProperty(AppState, name, descriptor)
    }
    set(status)
    Object.defineProperty(AppState, "addEventListener", {configurable: true, writable: true, value: listen})
    return {
        set,
        move: (next) => {
            set(next)
            for (const listener of [...listeners]) listener(next)
        },
        listening: () => listeners.size,
        onSubscribe: (callback) => {
            subscribing = callback
        },
        restore: () => {
            put("currentState", originalState)
            put("addEventListener", originalListen)
        },
    }
}
