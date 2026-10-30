// SPDX-License-Identifier: AGPL-3.0-only
import {useQueryClient} from "@tanstack/react-query"
import {useCallback, useState} from "react"
import type {MyOrganization} from "@/api/contract/organizations"
import {accountKeys, useMyOrganizations} from "@/query/account"
import {useServices, useSessionState} from "@/services/AppServices"

export type OrganizationChoiceState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {readonly kind: "ready"; readonly organizations: readonly MyOrganization[]; readonly current: string | undefined}

export interface OrganizationChoice {
    readonly state: OrganizationChoiceState
    /** The organization being switched to, while the session is saved. */
    readonly choosing: string | undefined
    readonly failed: boolean
    readonly choose: (id: string) => void
    readonly retry: () => void
}

/** The organization the calls act in (`Ostrom-Org`), chosen among the person's memberships. */
export function useOrganizationChoice(): OrganizationChoice {
    const session = useSessionState()
    const {sessionStore} = useServices()
    const queryClient = useQueryClient()
    const query = useMyOrganizations()
    const [choosing, setChoosing] = useState<string | undefined>(undefined)
    const [failed, setFailed] = useState(false)
    const current = session.status === "signed-in" ? session.session.orgId : undefined

    const choose = useCallback(
        (id: string) => {
            if (id === current) return
            setChoosing(id)
            setFailed(false)
            void (async () => {
                try {
                    await sessionStore.getState().chooseOrg(id)
                    // Nothing of one organization may show under the next one: every read but the list is read again.
                    await queryClient.resetQueries({predicate: (cached) => cached.queryKey[0] !== accountKeys.all[0]})
                } catch {
                    setFailed(true)
                } finally {
                    setChoosing(undefined)
                }
            })()
        },
        [current, sessionStore, queryClient],
    )

    const organizations = query.data
    const state: OrganizationChoiceState =
        organizations !== undefined
            ? {kind: "ready", organizations, current}
            : query.isError
              ? {kind: "error"}
              : {kind: "loading"}
    return {
        state,
        choosing,
        failed,
        choose,
        retry: () => {
            void query.refetch()
        },
    }
}
