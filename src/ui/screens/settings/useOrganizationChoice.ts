// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useEffect, useState} from "react"
import type {MyOrganization} from "@/api/contract/organizations"
import {useMyOrganizations} from "@/query/account"
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
                    // The reads of the organization are made again by the app root, once the screens use the new one.
                    await sessionStore.getState().chooseOrg(id)
                } catch {
                    setFailed(true)
                } finally {
                    setChoosing(undefined)
                }
            })()
        },
        [current, sessionStore],
    )

    const organizations = query.data
    // A single membership is the organization, even when the session names another the person has left.
    const only = organizations?.length === 1 ? organizations[0] : undefined
    useEffect(() => {
        // Straight to the session store, nothing shown. If it cannot be saved, the session stays as it was, and the server
        // still acts in the only membership of a call that names none (server#64); the next opening of Settings tries again.
        if (only !== undefined && only.id !== current) void sessionStore.getState().chooseOrg(only.id).catch(() => undefined)
    }, [only, current, sessionStore])
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
