// SPDX-License-Identifier: AGPL-3.0-only
import {type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient} from "@tanstack/react-query"
import type {ApprovalAnswer, ApprovalQueue, ApprovalView} from "@/api/contract/approvals"
import {receiptKeys} from "@/query/receipts"
import {useRepositories} from "@/services/AppServices"

export const approvalKeys = {
    all: ["approvals"] as const,
    queue: () => [...approvalKeys.all, "queue"] as const,
    detail: (id: string) => [...approvalKeys.all, "detail", id] as const,
}

/** The yes queue: the requests waiting for the person's answer, and the recent answers. */
export function useApprovalQueue(): UseQueryResult<ApprovalQueue> {
    const {approvals} = useRepositories()
    return useQuery({queryKey: approvalKeys.queue(), queryFn: () => approvals.queue()})
}

/** One request with all its evidence, and what the person may do with it. */
export function useApprovalView(id: string): UseQueryResult<ApprovalView> {
    const {approvals} = useRepositories()
    return useQuery({queryKey: approvalKeys.detail(id), queryFn: () => approvals.view(id)})
}

export interface AnswerVariables {
    readonly answer: ApprovalAnswer
    /** The digest of the request as the screen showed it: the server refuses if it changed since. */
    readonly shownDigest: string
    /** The reason of a no, for the agent. */
    readonly note?: string
}

/** Sends a yes or a no, once; the request and the waiting list are then read again from the answer and the server. */
export function useAnswer(id: string): UseMutationResult<ApprovalView, Error, AnswerVariables> {
    const {approvals} = useRepositories()
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({answer, shownDigest, note}: AnswerVariables) => approvals.answer(id, answer, shownDigest, note),
        onSuccess: (result) => {
            queryClient.setQueryData(approvalKeys.detail(id), result)
        },
        // The request, the waiting list and the history (an answer signs a receipt) are read again.
        onSettled: () =>
            Promise.all([
                queryClient.invalidateQueries({queryKey: approvalKeys.all}),
                queryClient.invalidateQueries({queryKey: receiptKeys.all}),
            ]),
    })
}
