// SPDX-License-Identifier: AGPL-3.0-only
import {useEffect, useMemo, useState} from "react"
import type {CheckAttestation, ReceiptListItem, ReceiptView} from "@/api/contract/receipts"
import type {ReceiptKey} from "@/domain/receipts/publicKey"
import {useReceiptKeys, useReceipts} from "@/query/receipts"
import {CHECKING, createReceiptChecks, keysFingerprint, type ReceiptChecks, type ShownCheck} from "./receiptChecks"
import {scheduleCheck} from "./scheduleCheck"

/**
 * A check the receipt reports, and where it ran. A run the workstation only declared proves nothing, and neither does
 * an origin read on a receipt whose signature this phone did not verify: it is `unverified`.
 */
export interface ReceiptCheckRun {
    readonly label: string
    /** The server's words, shown as they are. */
    readonly outcome: string
    /** `isolated_runner` or `ci` only on a verified receipt: the two origins shown as proven. */
    readonly badge: CheckAttestation | "unverified"
}

/** Run by Ostrom or the CI, on a verified receipt. */
export function isProven(run: ReceiptCheckRun): boolean {
    return run.badge === "isolated_runner" || run.badge === "ci"
}

type CheckNode = Pick<NonNullable<ReceiptView["nodes"]>[number], "label" | "outcome" | "attestation">

function checkRunOf(node: CheckNode, verified: boolean): ReceiptCheckRun {
    // A check signed before the field existed has none: it was the workstation's word.
    const attestation = node.attestation ?? "workstation_declared"
    const claimsRun = attestation === "isolated_runner" || attestation === "ci"
    return {
        label: node.label,
        outcome: node.outcome,
        badge: claimsRun && !verified ? "unverified" : attestation,
    }
}

/** One receipt of the history: what it says when the app can read it, and always what the phone found of its signature. */
export type ReceiptRowModel =
    | {
          /** Its position: the app cannot read it, so it has no id to show. */
          readonly key: string
          readonly readable: false
          readonly check: ShownCheck
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
          readonly check: ShownCheck
          /** The check nodes of a workflow receipt, in its order. */
          readonly checkRuns: readonly ReceiptCheckRun[]
      }

/** A receipt of the list, checked only when its row is shown. */
export interface ReceiptSource {
    /** The receipt's id, or its position when the app cannot read it. */
    readonly key: string
    readonly index: number
    readonly item: ReceiptListItem
}

export type HistoryState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {
          readonly kind: "ready"
          readonly rows: readonly ReceiptSource[]
          /** The checks with the instance keys of now, kept by receipt until the keys change. */
          readonly checks: ReceiptChecks
          /** The last refresh failed: these are the receipts read before. */
          readonly stale: boolean
      }

export interface HistoryViewModel {
    readonly state: HistoryState
    readonly refreshing: boolean
    readonly refresh: () => void
}

export function toReceiptRow(item: ReceiptListItem, index: number, check: ShownCheck): ReceiptRowModel {
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

function sourceOf(item: ReceiptListItem, index: number): ReceiptSource {
    return {key: item.readable ? item.view.id : `unreadable-${String(index)}`, index, item}
}

/** The signed receipts; each one is checked on this phone against the instance keys when its row is shown. */
export function useHistoryViewModel(): HistoryViewModel {
    const receipts = useReceipts()
    const key = useReceiptKeys()
    const {data, isError, isRefetching, refetch} = receipts
    // The key settles once; until then rows wait, so that no receipt shows as unverified for a moment.
    const keySettled = key.isSuccess || key.isError
    const keys = key.data ?? NO_KEYS
    const rows = useMemo(() => data?.items.map(sourceOf), [data])
    // The checks are kept while the keys stay the same, and forgotten when they change (a rotation).
    const fingerprint = keysFingerprint(keys)
    const [kept, setKept] = useState(() => ({fingerprint, checks: createReceiptChecks(keys)}))
    if (kept.fingerprint !== fingerprint) setKept({fingerprint, checks: createReceiptChecks(keys)})
    const state: HistoryState =
        rows !== undefined && keySettled
            ? {kind: "ready", rows, checks: kept.checks, stale: isError}
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

/**
 * What a row shows of its receipt's signature: the result kept for it, else neutral until the check that runs after
 * the row's first frame. A result found with other keys is never shown.
 */
export function useShownCheck(item: ReceiptListItem, checks: ReceiptChecks): ShownCheck {
    const [done, setDone] = useState<{readonly item: ReceiptListItem; readonly checks: ReceiptChecks; readonly check: ShownCheck}>()
    const found = done?.item === item && done.checks === checks ? done.check : checks.cached(item)
    useEffect(() => {
        if (found !== undefined) return undefined
        return scheduleCheck(() => {
            setDone({item, checks, check: checks.check(item)})
        })
    }, [found, item, checks])
    return found ?? CHECKING
}
