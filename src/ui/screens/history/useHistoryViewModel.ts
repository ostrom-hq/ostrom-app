// SPDX-License-Identifier: AGPL-3.0-only
import type {ReceiptListItem} from "@/api/contract/receipts"
import {useReceiptKey, useReceipts} from "@/query/receipts"
import {checkReceipt, type ReceiptCheck} from "./receiptCheck"

/** One receipt of the history: what it says when the app can read it, and always what the phone found of its signature. */
export type ReceiptRowModel =
    | {
          /** Its position: the app cannot read it, so it has no id to show. */
          readonly key: string
          readonly readable: false
          readonly check: ReceiptCheck
      }
    | {
          readonly key: string
          readonly readable: true
          /** `approved`, `rejected`… from the last decision, when there is one. */
          readonly result: string | undefined
          /** Who decided last, or who started the work when nobody decided yet. */
          readonly by: string
          readonly subject: string
          readonly at: string
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
        return {key: `unreadable-${String(index)}`, readable: false, check}
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
