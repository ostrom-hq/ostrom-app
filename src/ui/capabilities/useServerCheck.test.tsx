// SPDX-License-Identifier: AGPL-3.0-only
import {act, renderHook, waitFor} from "@testing-library/react-native"
import type {ReactNode} from "react"
import type {Capabilities} from "@/api/contract/capabilities"
import {ApiError} from "@/api/http/ApiError"
import {type AppServices, ServicesProvider} from "@/services/AppServices"
import {fakeAppState, type FakeAppState} from "@/test/appState"
import {fakeRepositories, signedInServices, testSession} from "@/test/fakeServices"
import {RECHECK_AFTER_MS, useServerCheck} from "./useServerCheck"

const capabilities: Capabilities = {apiVersion: 1, serverVersion: "1.4.0", features: ["receipts.v1"]}

let appState: FakeAppState
beforeEach(() => {
    appState = fakeAppState("active")
})

type Read = jest.Mock<Promise<Capabilities>, []>
const reading = (answer: () => Promise<Capabilities>): Read => jest.fn(answer)
afterEach(() => {
    appState.restore()
    jest.restoreAllMocks()
})

async function mount(answer: () => Promise<Capabilities>): Promise<{services: AppServices; read: Read; view: Awaited<ReturnType<typeof renderHook>>}> {
    const read = reading(answer)
    const services = await signedInServices({repositories: fakeRepositories({capabilities: {read}})})
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => <ServicesProvider services={services}>{children}</ServicesProvider>
    const view = await renderHook(
        () => {
            useServerCheck()
        },
        {wrapper},
    )
    return {services, read, view}
}

describe("useServerCheck", () => {
    it("reads the server at launch and keeps what it speaks and serves", async () => {
        const {services} = await mount(() => Promise.resolve(capabilities))
        await waitFor(() => {
            expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: {...testSession, capabilities}})
        })
    })

    it("blocks on a server that speaks another API version", async () => {
        const {services} = await mount(() => Promise.resolve({...capabilities, apiVersion: 2}))
        await waitFor(() => {
            expect(services.sessionStore.getState().incompatible).toEqual({apiVersion: 2})
        })
    })

    it("blocks on a server too old to have the route", async () => {
        const {services} = await mount(() => Promise.reject(new ApiError({kind: "refused", status: 404, type: "not_found", message: "No"})))
        await waitFor(() => {
            expect(services.sessionStore.getState().incompatible).toEqual({apiVersion: undefined})
        })
    })

    it("changes nothing when the server cannot be reached", async () => {
        const {services, read} = await mount(() => Promise.reject(new ApiError({kind: "network"})))
        await waitFor(() => {
            expect(read).toHaveBeenCalledTimes(1)
        })
        await act(async () => {
            await Promise.resolve()
        })
        expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: testSession})
        expect(services.sessionStore.getState().incompatible).toBeUndefined()
    })

    it("reads again when the app comes back after a while, and not before", async () => {
        const now = jest.spyOn(Date, "now").mockReturnValue(1_000)
        const {read} = await mount(() => Promise.resolve(capabilities))
        await waitFor(() => {
            expect(read).toHaveBeenCalledTimes(1)
        })
        await act(() => {
            appState.move("background")
            appState.move("active")
        })
        expect(read).toHaveBeenCalledTimes(1)
        now.mockReturnValue(1_000 + RECHECK_AFTER_MS)
        await act(() => {
            appState.move("active")
        })
        await waitFor(() => {
            expect(read).toHaveBeenCalledTimes(2)
        })
    })

    it("does not read twice at once", async () => {
        let answer: (value: Capabilities) => void = () => undefined
        const now = jest.spyOn(Date, "now").mockReturnValue(1_000)
        const {read} = await mount(
            () =>
                new Promise<Capabilities>((resolve) => {
                    answer = resolve
                }),
        )
        now.mockReturnValue(1_000 + RECHECK_AFTER_MS)
        await act(() => {
            appState.move("active")
        })
        expect(read).toHaveBeenCalledTimes(1)
        await act(async () => {
            answer(capabilities)
            await Promise.resolve()
        })
    })

    it("drops an answer that arrives after the screen is gone", async () => {
        let answer: (value: Capabilities) => void = () => undefined
        const {services, view} = await mount(
            () =>
                new Promise<Capabilities>((resolve) => {
                    answer = resolve
                }),
        )
        await view.unmount()
        expect(appState.listening()).toBe(0)
        await act(async () => {
            answer(capabilities)
            await Promise.resolve()
        })
        expect(services.sessionStore.getState().state).toEqual({status: "signed-in", session: testSession})
    })

    it("reads nothing signed out", async () => {
        const read = reading(() => Promise.resolve(capabilities))
        const services = await signedInServices({repositories: fakeRepositories({capabilities: {read}})})
        await services.sessionStore.getState().signOut()
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => <ServicesProvider services={services}>{children}</ServicesProvider>
        await renderHook(
        () => {
            useServerCheck()
        },
        {wrapper},
    )
        expect(read).not.toHaveBeenCalled()
        expect(appState.listening()).toBe(0)
    })

    it("reads the server again for a new session", async () => {
        const read = reading(() => Promise.resolve(capabilities))
        const services = await signedInServices({repositories: fakeRepositories({capabilities: {read}})})
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => <ServicesProvider services={services}>{children}</ServicesProvider>
        const store = services.sessionStore
        await renderHook(
        () => {
            useServerCheck()
        },
        {wrapper},
    )
        await waitFor(() => {
            expect(read).toHaveBeenCalledTimes(1)
        })
        await act(async () => {
            await store.getState().signIn({...testSession, token: "ost_2"})
        })
        await waitFor(() => {
            expect(read).toHaveBeenCalledTimes(2)
        })
    })
})
