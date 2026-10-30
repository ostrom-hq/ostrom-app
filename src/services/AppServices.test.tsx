import {renderHook} from "@testing-library/react-native"
import type {ReactNode} from "react"
import {fakeServices, testSession} from "@/test/fakeServices"
import {ServicesProvider, useRepositories, useServices} from "./AppServices"

describe("AppServices", () => {
    it("refuses to be used outside its provider", async () => {
        await expect(renderHook(() => useServices())).rejects.toThrow("useServices outside ServicesProvider")
    })

    it("gives the repositories of the signed-in session only", async () => {
        const services = fakeServices()
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
            <ServicesProvider services={services}>{children}</ServicesProvider>
        )
        await expect(renderHook(() => useRepositories(), {wrapper})).rejects.toThrow("useRepositories while signed out")
        await services.sessionStore.getState().signIn(testSession)
        const {result} = await renderHook(() => useRepositories(), {wrapper})
        expect(result.current).toBe(services.repositoriesFor(testSession))
    })
})
