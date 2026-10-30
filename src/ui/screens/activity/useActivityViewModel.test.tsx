// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {act, renderHook, waitFor} from "@testing-library/react-native"
import type {ReactNode} from "react"
import {type ActivityPage, ActivityPageSchema} from "@/api/contract/activity"
import {activity, olderActivity} from "@/api/mocks/fixtures"
import type {ActivityRepository} from "@/repositories/ActivityRepository"
import {ServicesProvider} from "@/services/AppServices"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {useActivityViewModel} from "./useActivityViewModel"

const first = ActivityPageSchema.parse(activity)
const older = ActivityPageSchema.parse(olderActivity)

describe("useActivityViewModel", () => {
    it("spins for a pull, not while an older page loads", async () => {
        // The first read answers at once; every later one waits until the test answers it.
        const later: ((page: ActivityPage) => void)[] = []
        let reads = 0
        const page = jest.fn<Promise<ActivityPage>, Parameters<ActivityRepository["page"]>>(() =>
            ++reads === 1
                ? Promise.resolve(first)
                : new Promise<ActivityPage>((resolve) => {
                      later.push(resolve)
                  }),
        )
        const services = await signedInServices({repositories: fakeRepositories({activity: {page}})})
        const client = testQueryClient()
        const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
            <ServicesProvider services={services}>
                <QueryClientProvider client={client}>{children}</QueryClientProvider>
            </ServicesProvider>
        )
        const {result} = await renderHook(() => useActivityViewModel(), {wrapper})
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
            later.shift()?.(first)
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
            expect(result.current.state).toMatchObject({kind: "ready", loadingMore: true})
        })
        expect(result.current.refreshing).toBe(false)
        await act(async () => {
            later.shift()?.(older)
            await Promise.resolve()
        })
        await waitFor(() => {
            expect(result.current.state).toMatchObject({kind: "ready", loadingMore: false, hasMore: false})
        })
    })
})
