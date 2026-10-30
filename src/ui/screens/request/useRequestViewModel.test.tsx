// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {act, renderHook, waitFor} from "@testing-library/react-native"
import type {ReactNode} from "react"
import type {AnswerResult, Approval} from "@/api/contract/approvals"
import type {ApprovalsRepository} from "@/repositories/ApprovalsRepository"
import type {AppServices} from "@/services/AppServices"
import {ServicesProvider} from "@/services/AppServices"
import {approvals, fakeRepositories, fakeServices, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {useRequestViewModel} from "./useRequestViewModel"

function wrapperOf(services: AppServices, client = testQueryClient()): (props: {children: ReactNode}) => React.JSX.Element {
    return ({children}) => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
}

async function servicesFor(
    approval: () => Promise<Approval>,
    confirm: jest.Mock,
    answer = jest.fn<Promise<AnswerResult>, Parameters<ApprovalsRepository["answer"]>>(),
): Promise<AppServices> {
    return signedInServices({
        biometrics: {confirm},
        repositories: fakeRepositories({approvals: {waiting: jest.fn(), approval, answer}}),
    })
}

describe("useRequestViewModel", () => {
    it("does not ask for Face ID before the request is loaded", async () => {
        const confirm = jest.fn()
        const services = await servicesFor(() => new Promise<Approval>(() => undefined), confirm)
        const {result} = await renderHook(() => useRequestViewModel("appr_0001"), {wrapper: wrapperOf(services)})
        expect(result.current.state.kind).toBe("loading")
        result.current.approve()
        await act(async () => {
            result.current.reread()
            await Promise.resolve()
        })
        expect(confirm).not.toHaveBeenCalled()
        expect(result.current.changed).toBe(false)
    })

    it("does not ask for Face ID on a request that is no longer waiting", async () => {
        const confirm = jest.fn()
        const services = await servicesFor(() => Promise.resolve(approvals.decided), confirm)
        const {result} = await renderHook(() => useRequestViewModel("appr_0000"), {wrapper: wrapperOf(services)})
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        expect(result.current.open).toBe(false)
        result.current.approve()
        expect(confirm).not.toHaveBeenCalled()
    })

    it("sends no decline without a reason, and trims the reason it sends", async () => {
        const confirm = jest.fn(() => Promise.resolve("confirmed"))
        const answer = jest.fn<Promise<AnswerResult>, Parameters<ApprovalsRepository["answer"]>>(() =>
            Promise.resolve({approval: {...approvals.pending, status: "rejected"}}),
        )
        const services = await servicesFor(() => Promise.resolve(approvals.pending), confirm, answer)
        const {result} = await renderHook(() => useRequestViewModel("appr_0001"), {wrapper: wrapperOf(services)})
        await waitFor(() => {
            expect(result.current.open).toBe(true)
        })
        await act(async () => {
            result.current.startDecline()
            result.current.setReason("   ")
            await Promise.resolve()
        })
        result.current.decline()
        expect(confirm).not.toHaveBeenCalled()
        await act(async () => {
            result.current.setReason("  Wait for the migration PR ")
            await Promise.resolve()
        })
        await act(async () => {
            result.current.decline()
            await Promise.resolve()
        })
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith("appr_0001", "reject", approvals.pending.shownDigest, "Wait for the migration PR")
        })
        await waitFor(() => {
            expect(result.current.declining).toBe(false)
        })
    })

    it("binds the answer to what the person first read, never to data a refetch swapped in", async () => {
        let current: Approval = approvals.pending
        const confirm = jest.fn(() => Promise.resolve("confirmed"))
        const answer = jest.fn<Promise<AnswerResult>, Parameters<ApprovalsRepository["answer"]>>(() =>
            Promise.resolve({approval: {...current, status: "approved"}}),
        )
        const services = await servicesFor(() => Promise.resolve(current), confirm, answer)
        const client = testQueryClient()
        const {result} = await renderHook(() => useRequestViewModel("appr_0001"), {wrapper: wrapperOf(services, client)})
        await waitFor(() => {
            expect(result.current.open).toBe(true)
        })
        current = {...approvals.pending, commits: ["c0ffee0"], shownDigest: "d-after-push"}
        await act(async () => {
            await client.invalidateQueries()
        })
        await waitFor(() => {
            expect(result.current.changed).toBe(true)
        })
        expect(result.current.problem).toBe("changed")
        result.current.approve()
        expect(confirm).not.toHaveBeenCalled()
        await act(async () => {
            result.current.reread()
            await Promise.resolve()
        })
        expect(result.current.changed).toBe(false)
        expect(result.current.problem).toBeUndefined()
        await act(async () => {
            result.current.approve()
            await Promise.resolve()
        })
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith("appr_0001", "approve", "d-after-push", undefined)
        })
    })

    it("is not mine when signed out", async () => {
        const services = fakeServices({
            repositories: fakeRepositories({approvals: {waiting: jest.fn(), approval: () => Promise.resolve(approvals.self), answer: jest.fn()}}),
        })
        await services.sessionStore.getState().hydrate()
        const {result} = await renderHook(() => useRequestViewModel("appr_0002"), {wrapper: wrapperOf(services)})
        expect(result.current.askedByMe).toBe(false)
    })
})
