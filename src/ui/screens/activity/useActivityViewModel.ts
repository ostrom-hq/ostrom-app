// SPDX-License-Identifier: AGPL-3.0-only
import type {ActivityItem} from "@/api/contract/activity"
import {useActivity} from "@/query/activity"

export type ActivityState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {readonly kind: "ready"; readonly items: readonly ActivityItem[]; readonly hasMore: boolean; readonly loadingMore: boolean}

export interface ActivityViewModel {
    readonly state: ActivityState
    readonly refreshing: boolean
    readonly refresh: () => void
    readonly loadMore: () => void
}

export function useActivityViewModel(): ActivityViewModel {
    const {data, isError, isRefetching, isFetchingNextPage, hasNextPage, refetch, fetchNextPage} = useActivity()
    const state: ActivityState =
        data !== undefined
            ? {kind: "ready", items: data.pages.flatMap((page) => page.items), hasMore: hasNextPage, loadingMore: isFetchingNextPage}
            : isError
              ? {kind: "error"}
              : {kind: "loading"}
    return {
        state,
        refreshing: isRefetching,
        refresh: () => {
            void refetch()
        },
        loadMore: () => {
            void fetchNextPage()
        },
    }
}
