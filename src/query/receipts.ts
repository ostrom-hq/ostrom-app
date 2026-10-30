// SPDX-License-Identifier: AGPL-3.0-only
import {type UseQueryResult, useQuery} from "@tanstack/react-query"
import type {ReceiptListItem} from "@/api/contract/receipts"
import type {ReceiptKey} from "@/domain/receipts/publicKey"
import {useRepositories} from "@/services/AppServices"
import {readInstanceKey} from "@/ui/screens/history/receiptCheck"

export const receiptKeys = {
    all: ["receipts"] as const,
    list: () => [...receiptKeys.all, "list"] as const,
    key: () => [...receiptKeys.all, "key"] as const,
}

export function useReceipts(): UseQueryResult<readonly ReceiptListItem[]> {
    const {receipts} = useRepositories()
    return useQuery({queryKey: receiptKeys.list(), queryFn: () => receipts.list()})
}

/** The instance key, checked against its announced id; `null` when the server serves an unusable key. */
export function useReceiptKey(): UseQueryResult<ReceiptKey | null> {
    const {receipts} = useRepositories()
    return useQuery({
        queryKey: receiptKeys.key(),
        queryFn: async () => readInstanceKey(await receipts.publicKey()) ?? null,
        staleTime: Infinity,
    })
}
