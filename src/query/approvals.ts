import {type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient} from "@tanstack/react-query"
import type {Ask, Decision, YesQueue} from "@/api/contract/yes"
import {useRepositories} from "@/services/AppServices"

export const approvalKeys = {
    all: ["yes"] as const,
    queue: () => [...approvalKeys.all, "queue"] as const,
    ask: (id: string) => [...approvalKeys.all, "ask", id] as const,
}

/** What waits for the person, and the last decided. */
export function useYesQueue(): UseQueryResult<YesQueue> {
    const {approvals} = useRepositories()
    return useQuery({queryKey: approvalKeys.queue(), queryFn: () => approvals.queue()})
}

/** One request with all its evidence. */
export function useAsk(id: string): UseQueryResult<Ask> {
    const {approvals} = useRepositories()
    return useQuery({queryKey: approvalKeys.ask(id), queryFn: () => approvals.ask(id)})
}

export interface DecideVariables {
    readonly decision: Decision
    /** The digest of the request as the screen showed it: the server refuses if it changed since. */
    readonly shownDigest: string
}

/** Sends a yes or a no, once; the request and the queue are then read again from the answer and the server. */
export function useDecide(id: string): UseMutationResult<Ask, Error, DecideVariables> {
    const {approvals} = useRepositories()
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({decision, shownDigest}: DecideVariables) => approvals.decide(id, decision, shownDigest),
        onSuccess: (ask) => {
            queryClient.setQueryData(approvalKeys.ask(id), ask)
        },
        onSettled: () => queryClient.invalidateQueries({queryKey: approvalKeys.all}),
    })
}
