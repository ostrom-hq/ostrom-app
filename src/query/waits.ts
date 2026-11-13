// SPDX-License-Identifier: AGPL-3.0-only
import {type InfiniteData, type UseInfiniteQueryResult, type UseMutationResult, type UseQueryResult, useInfiniteQuery, useMutation, useQuery, useQueryClient} from "@tanstack/react-query"
import type {Wait, WaitFilter, WaitsPage, WaitValues} from "@/api/contract/waits"
import {FEATURES} from "@/domain/capabilities/compatibility"
import {useFeature, useRepositories} from "@/services/AppServices"

export const waitKeys = {
    all: ["waits"] as const,
    list: (filter: WaitFilter) => [...waitKeys.all, "list", filter] as const,
    open: () => [...waitKeys.all, "open"] as const,
}

/** How often the badge reads the waits again while the app is open: a wait asked meanwhile shows without a pull. */
const BADGE_REFRESH_MS = 60_000

/** The most the badge counts in one read; more than this shows as "N+". */
export const BADGE_PAGE = 100

/** The waits asked of me with a filter, newest first, a page at a time. */
export function useWaits(filter: WaitFilter): UseInfiniteQueryResult<InfiniteData<WaitsPage, string | undefined>> {
    const {waits} = useRepositories()
    return useInfiniteQuery<WaitsPage, Error, InfiniteData<WaitsPage, string | undefined>, readonly string[], string | undefined>({
        queryKey: waitKeys.list(filter),
        queryFn: ({pageParam}) => waits.list(filter, {cursor: pageParam}),
        initialPageParam: undefined,
        getNextPageParam: (last) => last.next,
    })
}

/** The first page of what waits for me, to count it: read apart from the list, so the tab's badge lives without the screen. */
export function useOpenWaits(): UseQueryResult<WaitsPage> {
    const {waits} = useRepositories()
    const served = useFeature(FEATURES.waits)
    return useQuery({
        queryKey: waitKeys.open(),
        queryFn: () => waits.list("open", {limit: BADGE_PAGE}),
        enabled: served,
        refetchInterval: BADGE_REFRESH_MS,
    })
}

export interface AnswerWaitVariables {
    readonly id: string
    readonly action: string
    readonly values: WaitValues
}

/** Sends an answer once; the lists and the badge are then read again, whether it went through or the wait had closed. */
export function useAnswerWait(): UseMutationResult<Wait, Error, AnswerWaitVariables> {
    const {waits} = useRepositories()
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({id, action, values}: AnswerWaitVariables) => waits.answer(id, action, values),
        onSettled: () => queryClient.invalidateQueries({queryKey: waitKeys.all}),
    })
}
