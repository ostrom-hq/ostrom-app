// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback} from "react"
import {SUPPORTED_API_VERSION} from "@/domain/capabilities/compatibility"
import {useIncompatibleServer, useRepositories, useServices} from "@/services/AppServices"

export interface IncompatibleViewModel {
    /** The API version the server speaks; undefined for a server too old to say. */
    readonly version: number | undefined
    readonly supported: number
    readonly signOut: () => void
}

/** The blocking screen of a server this app does not speak: only a sign-out leaves it. */
export function useIncompatibleViewModel(): IncompatibleViewModel {
    const {sessionStore} = useServices()
    const {account} = useRepositories()
    const incompatible = useIncompatibleServer()
    const signOut = useCallback(() => {
        // Best effort, never waited for: the token is revoked on the server, which may not answer. The keychain is
        // cleared at once.
        void account.logout().catch(() => undefined)
        void sessionStore.getState().signOut()
    }, [account, sessionStore])
    return {version: incompatible?.apiVersion, supported: SUPPORTED_API_VERSION, signOut}
}
