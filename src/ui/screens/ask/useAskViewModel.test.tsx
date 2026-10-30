// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {renderHook, waitFor} from "@testing-library/react-native"
import type {ReactNode} from "react"
import type {Ask} from "@/api/contract/yes"
import {ServicesProvider} from "@/services/AppServices"
import {asks, fakeRepositories, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {useAskViewModel} from "./useAskViewModel"

async function wrapperFor(ask: () => Promise<Ask>, confirm: jest.Mock): Promise<(props: {children: ReactNode}) => React.JSX.Element> {
    const services = await signedInServices({
        biometrics: {confirm},
        repositories: fakeRepositories({approvals: {queue: jest.fn(), ask, decide: jest.fn()}}),
    })
    const client = testQueryClient()
    return ({children}) => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
}

describe("useAskViewModel", () => {
    it("does not ask for Face ID before the request is loaded", async () => {
        const confirm = jest.fn()
        const wrapper = await wrapperFor(() => new Promise<Ask>(() => undefined), confirm)
        const {result} = await renderHook(() => useAskViewModel("appr_0001"), {wrapper})
        expect(result.current.state.kind).toBe("loading")
        result.current.decide("approved")
        expect(confirm).not.toHaveBeenCalled()
    })

    it("does not ask for Face ID on a request I cannot decide", async () => {
        const confirm = jest.fn()
        const wrapper = await wrapperFor(() => Promise.resolve(asks.self), confirm)
        const {result} = await renderHook(() => useAskViewModel("appr_0002"), {wrapper})
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        expect(result.current.canDecide).toBe(false)
        result.current.decide("approved")
        expect(confirm).not.toHaveBeenCalled()
    })
})
