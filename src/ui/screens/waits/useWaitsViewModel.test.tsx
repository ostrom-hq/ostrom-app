// SPDX-License-Identifier: AGPL-3.0-only
import {type QueryClient, QueryClientProvider} from "@tanstack/react-query"
import {act, type RenderHookResult, renderHook, waitFor} from "@testing-library/react-native"
import type {ReactNode} from "react"
import {type Wait, WaitSchema, type WaitsPage} from "@/api/contract/waits"
import {confirmWait, openWait} from "@/api/mocks/fixtures"
import type {WaitsRepository} from "@/repositories/WaitsRepository"
import {ServicesProvider} from "@/services/AppServices"
import {fakeRepositories, signedInServices, testSession} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {useWaitsViewModel, type WaitsViewModel} from "./useWaitsViewModel"

const open = WaitSchema.parse(openWait)
const confirm = WaitSchema.parse(confirmWait)

async function mount(
    list: WaitsRepository["list"],
    answer: WaitsRepository["answer"] = jest.fn(),
    features?: readonly string[],
): Promise<RenderHookResult<WaitsViewModel, unknown> & {client: QueryClient}> {
    const services = await signedInServices({repositories: fakeRepositories({waits: {list, answer}})})
    if (features !== undefined) {
        await services.sessionStore.getState().signIn({...testSession, capabilities: {apiVersion: 1, serverVersion: "1.4.0", features: [...features]}})
    }
    const client = testQueryClient()
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    const rendered = await renderHook(() => useWaitsViewModel(), {wrapper})
    return {...rendered, client}
}

describe("useWaitsViewModel", () => {
    it("spins for a pull, not while an older page loads", async () => {
        const later: ((page: WaitsPage) => void)[] = []
        let reads = 0
        const list = jest.fn<Promise<WaitsPage>, Parameters<WaitsRepository["list"]>>((_filter, options) =>
            options?.limit === 100 || ++reads === 1
                ? Promise.resolve({items: [open], next: open.id})
                : new Promise<WaitsPage>((resolve) => {
                      later.push(resolve)
                  }),
        )
        const {result} = await mount(list)
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        await act(async () => {
            result.current.refresh()
            await Promise.resolve()
        })
        await waitFor(() => {
            expect(result.current.refreshing).toBe(true)
        })
        await act(async () => {
            later.shift()?.({items: [open], next: open.id})
            await Promise.resolve()
        })
        await waitFor(() => {
            expect(result.current.refreshing).toBe(false)
        })
        await act(async () => {
            result.current.loadMore()
            await Promise.resolve()
        })
        await waitFor(() => {
            expect(result.current.state).toMatchObject({kind: "ready", loadingMore: true, hasMore: true})
        })
        expect(result.current.refreshing).toBe(false)
        await act(async () => {
            later.shift()?.({items: [confirm]})
            await Promise.resolve()
        })
        await waitFor(() => {
            expect(result.current.state).toMatchObject({kind: "ready", loadingMore: false, hasMore: false})
        })
    })

    it("keeps two edits made before the next render", async () => {
        const {result} = await mount(() => Promise.resolve({items: [open]}))
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        await act(async () => {
            result.current.edit(open.id, "ttl", {text: "90"})
            result.current.edit(open.id, "reason", {text: "fast"})
            await Promise.resolve()
        })
        const row = result.current.state.kind === "ready" ? result.current.state.rows[0] : undefined
        expect(row?.fields.map((model) => model.draft.text)).toEqual(["", "90", "fast"])
    })

    it("does not spin when the list is read again without a pull", async () => {
        const {result, client} = await mount(() => Promise.resolve({items: [open]}))
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(result.current.refreshing).toBe(false)
    })

    it("counts the open waits for the badge, and says when more pages remain", async () => {
        const items: Wait[] = [open, confirm]
        const {result} = await mount(() => Promise.resolve({items}))
        await waitFor(() => {
            expect(result.current.badge).toEqual({count: 2, more: false})
        })
    })

    it("asks nothing for the badge of a server that does not serve waits", async () => {
        const none: Wait[] = []
        const list = jest.fn(() => Promise.resolve({items: none}))
        const { result } = await mount(list, jest.fn(), ["gates.v1"])
        await waitFor(() => {
            expect(list).toHaveBeenCalledTimes(1)
        })
        expect(result.current.badge).toBeUndefined()
    })

    it("ignores an answer or an edit for a wait it does not list, and an action it does not know", async () => {
        const answer = jest.fn()
        const { result } = await mount(() => Promise.resolve({items: [open]}), answer)
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        await act(async () => {
            result.current.submit("nope", "choose")
            result.current.submit(open.id, "nope")
            result.current.edit("nope", "store", {other: "x"})
            result.current.edit(open.id, "nope", {other: "x"})
            await Promise.resolve()
        })
        expect(answer).not.toHaveBeenCalled()
        expect(result.current.state).toMatchObject({kind: "ready", rows: [{problem: undefined, sending: false}]})
    })

    it("ignores an edit of a wait that asks no field", async () => {
        const { result } = await mount(() => Promise.resolve({items: [confirm]}))
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        await act(async () => {
            result.current.edit(confirm.id, "store", {other: "x"})
            await Promise.resolve()
        })
        expect(result.current.state).toMatchObject({kind: "ready", rows: [{fields: []}]})
    })

    it("does not answer a wait that is not open", async () => {
        const answer = jest.fn()
        const { result } = await mount(() => Promise.resolve({items: [{...open, status: "resolved"}]}), answer)
        await waitFor(() => {
            expect(result.current.state.kind).toBe("ready")
        })
        await act(async () => {
            result.current.submit(open.id, "defer")
            await Promise.resolve()
        })
        expect(answer).not.toHaveBeenCalled()
    })
})
