// SPDX-License-Identifier: AGPL-3.0-only
import {useMemo} from "react"
import type {CheckAttestation, ReceiptListItem} from "@/api/contract/receipts"
import type {ReceiptKey} from "@/domain/receipts/publicKey"
import {useReceiptKeys, useReceipts} from "@/query/receipts"
import {checkReceipt, type ReceiptCheck} from "./receiptCheck"

/**
 * A check the receipt reports, and where it ran. A run the workstation only declared proves nothing, and neither does
 * an origin read on a receipt whose signature this phone did not verify: it is `unverified`.
 */
export interface ReceiptCheckRun {
    readonly label: string
    /** The server's words, shown as they are. */
    readonly outcome: string
    readonly badge: CheckAttestation | "unverified"
    /** Run by Ostrom or the CI, on a verified receipt. */
    readonly proven: boolean
}

interface CheckNode {
    readonly label: string
    readonly outcome: string
    readonly attestation?: CheckAttestation | undefined
}

function checkRunOf(node: CheckNode, verified: boolean): ReceiptCheckRun {
    // A check signed before the field existed has none: it was the workstation's word.
    const attestation = node.attestation ?? "workstation_declared"
    const claimsRun = attestation === "isolated_runner" || attestation === "ci"
    return {
        label: node.label,
        outcome: node.outcome,
        badge: claimsRun && !verified ? "unverified" : attestation,
        proven: claimsRun && verified,
    }
}

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
          /** The last decision was the asker's own yes (the only approver): the history says so. */
          readonly selfApproved: boolean
          /** Who decided last, or who started the work when nobody decided yet. */
          readonly by: string
          readonly subject: string
          readonly at: string
          readonly check: ReceiptCheck
          /** The check nodes of a workflow receipt, in its order. */
          readonly checkRuns: readonly ReceiptCheckRun[]
      }

export type HistoryState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {
          readonly kind: "ready"
          readonly rows: readonly ReceiptRowModel[]
          /** The last refresh failed: these are the receipts read before. */
          readonly stale: boolean
      }

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
        selfApproved: decision?.selfApproved === true,
        by: decision?.by ?? view.initiator,
        subject: `${view.subject.kind} ${view.subject.id}`,
        at: decision?.at ?? view.createdAt,
        check,
        checkRuns: (view.nodes ?? []).filter((node) => node.kind === "check").map((node) => checkRunOf(node, check.kind === "verified")),
    }
}

const NO_KEYS: ReadonlyMap<string, ReceiptKey> = new Map()

/** The signed receipts, each one checked on this phone against the instance keys. */
export function useHistoryViewModel(): HistoryViewModel {
    const receipts = useReceipts()
    const key = useReceiptKeys()
    const {data, isError, isRefetching, refetch} = receipts
    // The key settles once; until then rows wait, so that no receipt shows as unverified for a moment.
    const keySettled = key.isSuccess || key.isError
    const keys = key.data ?? NO_KEYS
    // A signature check is a hash and an Ed25519 verification: done again only when the receipts or the keys change.
    const rows = useMemo(
        () => data?.items.map((item, index) => toReceiptRow(item, index, checkReceipt(item, keys))),
        [data, keys],
    )
    const state: HistoryState =
        rows !== undefined && keySettled
            ? {kind: "ready", rows, stale: isError}
            : isError
              ? {kind: "error"}
              : {kind: "loading"}
    return {
        state,
        refreshing: isRefetching,
        refresh: () => {
            void refetch()
            // The keys too: a key the instance rotated, or one that failed, is read again.
            void key.refetch()
        },
    }
}
