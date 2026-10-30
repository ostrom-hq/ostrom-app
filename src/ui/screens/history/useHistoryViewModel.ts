import type {ReceiptListItem} from "@/api/contract/receipts"
import {useReceiptKey, useReceipts} from "@/query/receipts"
import {checkReceipt, type ReceiptCheck} from "./receiptCheck"

export interface ReceiptRowModel {
    /** The receipt id, or its position when the app cannot read it. */
    readonly key: string
    readonly readable: boolean
    /** `approved`, `rejected`… from the last decision, when there is one. */
    readonly result: string | undefined
    readonly by: string | undefined
    readonly subject: string | undefined
    readonly at: string | undefined
    readonly check: ReceiptCheck
}

export type HistoryState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {readonly kind: "ready"; readonly rows: readonly ReceiptRowModel[]}

export interface HistoryViewModel {
    readonly state: HistoryState
    readonly refreshing: boolean
    readonly refresh: () => void
}

export function toReceiptRow(item: ReceiptListItem, index: number, check: ReceiptCheck): ReceiptRowModel {
    if (!item.readable) {
        return {key: `unreadable-${String(index)}`, readable: false, result: undefined, by: undefined, subject: undefined, at: undefined, check}
    }
    const {view} = item
    const decision = view.decisions.at(-1)
    return {
        key: view.id,
        readable: true,
        result: decision?.result,
        by: decision?.by ?? view.initiator,
        subject: `${view.subject.kind} ${view.subject.id}`,
        at: decision?.at ?? view.createdAt,
        check,
    }
}

/** The signed receipts, each one checked on this phone against the instance key. */
export function useHistoryViewModel(): HistoryViewModel {
    const receipts = useReceipts()
    const key = useReceiptKey()
    const {data, isError, isRefetching, refetch} = receipts
    // The key settles once; until then rows wait, so that no receipt shows as unverified for a moment.
    const keySettled = key.isSuccess || key.isError
    const instanceKey = key.data ?? undefined
    const state: HistoryState =
        isError
            ? {kind: "error"}
            : data === undefined || !keySettled
              ? {kind: "loading"}
              : {kind: "ready", rows: data.map((item, index) => toReceiptRow(item, index, checkReceipt(item, instanceKey)))}
    return {
        state,
        refreshing: isRefetching,
        refresh: () => {
            void refetch()
            if (key.isError) void key.refetch()
        },
    }
}
