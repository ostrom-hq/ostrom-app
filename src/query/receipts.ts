import {type UseQueryResult, useQuery} from "@tanstack/react-query"
import type {ReceiptPage} from "@/api/contract/receipts"
import type {ReceiptKey} from "@/domain/receipts/publicKey"
import {useRepositories} from "@/services/AppServices"
import {readInstanceKeys} from "@/ui/screens/history/receiptCheck"

export const receiptKeys = {
    all: ["receipts"] as const,
    list: () => [...receiptKeys.all, "list"] as const,
    keys: () => [...receiptKeys.all, "keys"] as const,
}

export function useReceipts(): UseQueryResult<ReceiptPage> {
    const {receipts} = useRepositories()
    return useQuery({queryKey: receiptKeys.list(), queryFn: () => receipts.list()})
}

/** The instance keys, each checked against its announced id; an unusable key is left out. */
export function useReceiptKeys(): UseQueryResult<ReadonlyMap<string, ReceiptKey>> {
    const {receipts} = useRepositories()
    return useQuery({
        queryKey: receiptKeys.keys(),
        queryFn: async () => readInstanceKeys(await receipts.keys()),
        // Kept a while, not forever: a rotated key is picked up without a restart.
        staleTime: 5 * 60_000,
        // Not retried: the rows wait for the keys, and a missing key is said on each row (pull to fetch it again).
        retry: false,
    })
}
