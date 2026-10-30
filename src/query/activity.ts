import {type InfiniteData, type UseInfiniteQueryResult, useInfiniteQuery} from "@tanstack/react-query"
import type {ActivityPage} from "@/api/contract/activity"
import {useRepositories} from "@/services/AppServices"

export const activityKeys = {all: ["activity"] as const}

/** The activity of agents and people, newest first, a page at a time. */
export function useActivity(): UseInfiniteQueryResult<InfiniteData<ActivityPage, string | undefined>> {
    const {activity} = useRepositories()
    return useInfiniteQuery<ActivityPage, Error, InfiniteData<ActivityPage, string | undefined>, readonly string[], string | undefined>({
        queryKey: activityKeys.all,
        queryFn: ({pageParam}) => activity.page(pageParam),
        initialPageParam: undefined,
        getNextPageParam: (last) => last.nextCursor ?? undefined,
    })
}
