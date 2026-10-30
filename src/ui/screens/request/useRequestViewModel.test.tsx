// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {act, renderHook, waitFor} from "@testing-library/react-native"
import type {ReactNode} from "react"
import type {Approval, ApprovalView} from "@/api/contract/approvals"
import type {ApprovalsRepository} from "@/repositories/ApprovalsRepository"
import type {AppServices} from "@/services/AppServices"
import {ServicesProvider} from "@/services/AppServices"
import {approvals, fakeRepositories, signedInServices, viewOf} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {useRequestViewModel} from "./useRequestViewModel"

function wrapperOf(services: AppServices, client = testQueryClient()): (props: {children: ReactNode}) => React.JSX.Element {
    return ({children}) => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
}

/** A server that shows `approval` to `rev-b` (with `you` as it says), and a phone whose biometrics are `confirm`. */
async function servicesFor(
    approval: () => Promise<Approval>,
    confirm: jest.Mock,
    answer = jest.fn<Promise<ApprovalView>, Parameters<ApprovalsRepository["answer"]>>(),
    you: Partial<ApprovalView["you"]> = {},
): Promise<AppServices> {
    const view = async (_id: string): Promise<ApprovalView> => viewOf(await approval(), you)
    return signedInServices({
        biometrics: {confirm},
        repositories: fakeRepositories({approvals: {queue: jest.fn(), view, answer}}),
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

    it("takes no yes on a diff the phone does not show in full", async () => {
        const confirm = jest.fn()
        const answer = jest.fn<Promise<ApprovalView>, Parameters<ApprovalsRepository["answer"]>>()
        const services = await servicesFor(() => Promise.resolve({...approvals.pending, diffTruncated: true}), confirm, answer)
        const {result} = await renderHook(() => useRequestViewModel("appr_0001"), {wrapper: wrapperOf(services)})
        await waitFor(() => {
            expect(result.current.open).toBe(true)
        })
        await act(async () => {
            result.current.approve()
            await Promise.resolve()
        })
        expect(confirm).not.toHaveBeenCalled()
        expect(answer).not.toHaveBeenCalled()
        expect(result.current.sending).toBeUndefined()
    })

    it("sends no decline without a reason, and trims the reason it sends", async () => {
        const confirm = jest.fn(() => Promise.resolve("confirmed"))
        const answer = jest.fn<Promise<ApprovalView>, Parameters<ApprovalsRepository["answer"]>>(() =>
            Promise.resolve(viewOf({...approvals.pending, status: "rejected"})),
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
        const answer = jest.fn<Promise<ApprovalView>, Parameters<ApprovalsRepository["answer"]>>(() =>
            Promise.resolve(viewOf({...current, status: "approved"})),
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

    it("takes no yes the server refuses, says its reason, and asks no Face ID for it", async () => {
        const confirm = jest.fn()
        const answer = jest.fn<Promise<ApprovalView>, Parameters<ApprovalsRepository["answer"]>>()
        const reason = "Only a maintainer of acme-api approves a deploy"
        const services = await servicesFor(() => Promise.resolve(approvals.pending), confirm, answer, {canApprove: false, reason})
        const {result} = await renderHook(() => useRequestViewModel("appr_0001"), {wrapper: wrapperOf(services)})
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        expect(result.current.mayApprove).toBe(false)
        expect(result.current.noApprove).toEqual({kind: "refused", reason})
        result.current.approve()
        expect(confirm).not.toHaveBeenCalled()
        expect(answer).not.toHaveBeenCalled()
    })

    it("is mine when the server says the request is mine", async () => {
        const services = await servicesFor(() => Promise.resolve(approvals.pending), jest.fn(), undefined, {self: true})
        const {result} = await renderHook(() => useRequestViewModel("appr_0001"), {wrapper: wrapperOf(services)})
        await waitFor(() => {
            expect(result.current.askedByMe).toBe(true)
        })
    })
})
