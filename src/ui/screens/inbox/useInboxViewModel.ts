// SPDX-License-Identifier: AGPL-3.0-only
import type {Ask} from "@/api/contract/yes"
import {useYesQueue} from "@/query/approvals"

/** One line of the inbox: what is asked, where, by whom. */
export interface AskRowModel {
    readonly id: string
    readonly title: string
    readonly status: Ask["status"]
    /** Repository and asker, when known, for the second line. */
    readonly detail: string
    readonly yours: boolean
}

export type InboxState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {readonly kind: "ready"; readonly pending: readonly AskRowModel[]; readonly recent: readonly AskRowModel[]}

export interface InboxViewModel {
    readonly state: InboxState
    readonly refreshing: boolean
    readonly refresh: () => void
}

export function toRow(ask: Ask): AskRowModel {
    const repo = ask.what.repo ?? ask.what.sessionRepo
    const asker = ask.who.dev ?? ask.askedBy[0]
    return {
        id: ask.id,
        title: ask.title,
        status: ask.status,
        detail: [repo, asker].filter((part): part is string => typeof part === "string" && part !== "").join(" · "),
        yours: ask.you?.self === true,
    }
}

export function useInboxViewModel(): InboxViewModel {
    const query = useYesQueue()
    const {data, isError, isRefetching, refetch} = query
    const state: InboxState =
        data !== undefined
            ? {kind: "ready", pending: data.pending.map(toRow), recent: data.recent.map(toRow)}
            : isError
              ? {kind: "error"}
              : {kind: "loading"}
    return {
        state,
        refreshing: isRefetching,
        refresh: () => {
            void refetch()
        },
    }
}
