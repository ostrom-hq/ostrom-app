import type {Approval} from "@/api/contract/approvals"
import {useWaitingApprovals} from "@/query/approvals"
import {useSessionState} from "@/services/AppServices"

/** One line of the inbox: what is asked, where, by whom. It only opens the request: no answer from a list. */
export interface RequestRowModel {
    readonly id: string
    readonly title: string
    readonly status: Approval["status"]
    /** Repository and asker, when known, for the second line. */
    readonly detail: string
    /** The person asked for it themselves. */
    readonly yours: boolean
}

export type InboxState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {
          readonly kind: "ready"
          readonly rows: readonly RequestRowModel[]
          /** The last refresh failed: the rows are the last list read, perhaps out of date. */
          readonly stale: boolean
      }

export interface InboxViewModel {
    readonly state: InboxState
    readonly refreshing: boolean
    readonly refresh: () => void
}

export function toRow(approval: Approval, login: string | undefined): RequestRowModel {
    const askedBy = approval.askedBy ?? []
    return {
        id: approval.id,
        title: approval.title,
        status: approval.status,
        detail: [approval.repo, askedBy[0]].filter((part): part is string => part !== undefined && part !== "").join(" · "),
        yours: login !== undefined && askedBy.includes(login),
    }
}

export function useInboxViewModel(): InboxViewModel {
    const session = useSessionState()
    const login = session.status === "signed-in" ? session.session.user.login : undefined
    const {data, isError, isRefetching, refetch} = useWaitingApprovals()
    const state: InboxState =
        data !== undefined
            ? {kind: "ready", rows: data.items.map((approval) => toRow(approval, login)), stale: isError}
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
