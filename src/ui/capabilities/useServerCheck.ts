// SPDX-License-Identifier: AGPL-3.0-only
import {useEffect} from "react"
import {AppState} from "react-native"
import {assessServer} from "@/domain/capabilities/compatibility"
import {useServices, useSessionState} from "@/services/AppServices"

/** The server is not asked again when the app comes back to the foreground within this time. */
export const RECHECK_AFTER_MS = 10 * 60_000

/**
 * While signed in: reads what the server speaks and serves at launch (or sign-in), and again when the app comes back
 * to the foreground, at most every {@link RECHECK_AFTER_MS}. A server that speaks another API version blocks the app; a
 * server that cannot be reached changes nothing, and never signs out.
 */
export function useServerCheck(): void {
    const {sessionStore, repositoriesFor} = useServices()
    const state = useSessionState()
    const token = state.status === "signed-in" ? state.session.token : undefined
    const serverUrl = state.status === "signed-in" ? state.session.serverUrl : undefined
    useEffect(() => {
        if (token === undefined || serverUrl === undefined) return
        let mounted = true
        let reading = false
        let readAt = Number.NEGATIVE_INFINITY
        const check = async (): Promise<void> => {
            const current = sessionStore.getState().state
            if (reading || current.status !== "signed-in" || current.session.token !== token) return
            reading = true
            readAt = Date.now()
            const outcome = await assessServer(() => repositoriesFor(current.session).capabilities.read())
            reading = false
            if (!mounted) return
            switch (outcome.kind) {
                case "compatible":
                    await sessionStore.getState().noteCapabilities(token, outcome.capabilities)
                    break
                case "incompatible":
                    sessionStore.getState().noteIncompatible(token, outcome.apiVersion)
                    break
                case "unknown":
                    break
            }
        }
        void check()
        const subscription = AppState.addEventListener("change", (status) => {
            if (status === "active" && Date.now() - readAt >= RECHECK_AFTER_MS) void check()
        })
        return () => {
            mounted = false
            subscription.remove()
        }
    }, [token, serverUrl, sessionStore, repositoriesFor])
}
