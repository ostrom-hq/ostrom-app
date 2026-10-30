// SPDX-License-Identifier: AGPL-3.0-only
import {type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient} from "@tanstack/react-query"
import type {AnswerResult, Approval, ApprovalAnswer, ApprovalPage} from "@/api/contract/approvals"
import {receiptKeys} from "@/query/receipts"
import {useRepositories} from "@/services/AppServices"

export const approvalKeys = {
    all: ["approvals"] as const,
    waiting: () => [...approvalKeys.all, "waiting"] as const,
    detail: (id: string) => [...approvalKeys.all, "detail", id] as const,
}

/** The requests waiting for the person's answer. */
export function useWaitingApprovals(): UseQueryResult<ApprovalPage> {
    const {approvals} = useRepositories()
    return useQuery({queryKey: approvalKeys.waiting(), queryFn: () => approvals.waiting()})
}

/** One request with all its evidence. */
export function useApproval(id: string): UseQueryResult<Approval> {
    const {approvals} = useRepositories()
    return useQuery({queryKey: approvalKeys.detail(id), queryFn: () => approvals.approval(id)})
}

export interface AnswerVariables {
    readonly answer: ApprovalAnswer
    /** The digest of the request as the screen showed it: the server refuses if it changed since. */
    readonly shownDigest: string
    /** The reason of a no, for the agent. */
    readonly note?: string
}

/** Sends a yes or a no, once; the request and the waiting list are then read again from the answer and the server. */
export function useAnswer(id: string): UseMutationResult<AnswerResult, Error, AnswerVariables> {
    const {approvals} = useRepositories()
    const queryClient = useQueryClient()
    return useMutation({
        mutationFn: ({answer, shownDigest, note}: AnswerVariables) => approvals.answer(id, answer, shownDigest, note),
        onSuccess: (result) => {
            queryClient.setQueryData(approvalKeys.detail(id), result.approval)
        },
        // The request, the waiting list and the history (an answer signs a receipt) are read again.
        onSettled: () =>
            Promise.all([
                queryClient.invalidateQueries({queryKey: approvalKeys.all}),
                queryClient.invalidateQueries({queryKey: receiptKeys.all}),
            ]),
    })
}
