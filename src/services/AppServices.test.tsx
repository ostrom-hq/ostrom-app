// SPDX-License-Identifier: AGPL-3.0-only
import {renderHook} from "@testing-library/react-native"
import type {ReactNode} from "react"
import {fakeServices, testSession} from "@/test/fakeServices"
import {ServicesProvider, SIGNED_OUT_REPOSITORIES, useRepositories, useServices} from "./AppServices"

describe("AppServices", () => {
    it("refuses to be used outside its provider", async () => {
        await expect(renderHook(() => useServices())).rejects.toThrow("useServices outside ServicesProvider")
    })

    it("gives the repositories of the signed-in session only", async () => {
        const services = fakeServices()
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
            <ServicesProvider services={services}>{children}</ServicesProvider>
        )
        const signedOut = await renderHook(() => useRepositories(), {wrapper})
        expect(signedOut.result.current).toBe(SIGNED_OUT_REPOSITORIES)
        await expect(signedOut.result.current.approvals.waiting()).rejects.toMatchObject({problem: {kind: "unauthorized"}})
        await services.sessionStore.getState().signIn(testSession)
        const {result} = await renderHook(() => useRepositories(), {wrapper})
        expect(result.current).toBe(services.repositoriesFor(testSession))
    })
})
