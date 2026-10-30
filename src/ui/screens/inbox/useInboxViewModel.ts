// SPDX-License-Identifier: AGPL-3.0-only
import type {Approval, ApprovalView} from "@/api/contract/approvals"
import {isOrgRequired} from "@/api/http/ApiError"
import {useApprovalQueue} from "@/query/approvals"

/** One line of the inbox: what is asked, where, by whom. It only opens the request: no answer from a list. */
export interface RequestRowModel {
    readonly id: string
    readonly title: string
    readonly status: Approval["status"]
    /** Repository and asker, when known, for the second line. */
    readonly detail: string
    /** The person asked for it themselves, as the server says. */
    readonly yours: boolean
}

export type InboxState =
    | {readonly kind: "loading"}
    /** `orgRequired`: the server wants the organization named, which the app cannot do yet. */
    | {readonly kind: "error"; readonly orgRequired: boolean}
    | {
          readonly kind: "ready"
          /** What waits for the person's answer. */
          readonly rows: readonly RequestRowModel[]
          /** What was answered lately, after the waiting requests. */
          readonly recent: readonly RequestRowModel[]
          /** The last refresh failed: the rows are the last list read, perhaps out of date. */
          readonly stale: boolean
          /** The failed refresh wants the organization named: said instead of "out of date". */
          readonly orgRequired: boolean
      }

export interface InboxViewModel {
    readonly state: InboxState
    readonly refreshing: boolean
    readonly refresh: () => void
}

export function toRow({approval, you}: ApprovalView): RequestRowModel {
    const askedBy = approval.askedBy ?? []
    return {
        id: approval.id,
        title: approval.title,
        status: approval.status,
        detail: [approval.repo, askedBy[0]].filter((part): part is string => part !== undefined && part !== "").join(" · "),
        yours: you.self,
    }
}

export function useInboxViewModel(): InboxViewModel {
    const {data, error, isError, isRefetching, refetch} = useApprovalQueue()
    const state: InboxState =
        data !== undefined
            ? {
                  kind: "ready",
                  rows: data.pending.map(toRow),
                  recent: data.recent.map(toRow),
                  stale: isError,
                  orgRequired: isError && isOrgRequired(error),
              }
            : isError
              ? {kind: "error", orgRequired: isOrgRequired(error)}
              : {kind: "loading"}
    return {
        state,
        refreshing: isRefetching,
        refresh: () => {
            void refetch()
        },
    }
}
