// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useState} from "react"
import {useServices} from "@/services/AppServices"
import type {SignInFailure} from "@/services/signIn/GithubSignIn"
import {DEFAULT_SERVER_URL} from "@/config/env"
import {assessServer, SUPPORTED_API_VERSION} from "@/domain/capabilities/compatibility"
import {normalizeServerUrl} from "./serverUrl"

/** `unexpected`: the browser or the keychain failed outside the sign-in itself. */
export type LoginError = "invalid-url" | "unexpected" | "unsupported-server" | "server-too-old" | SignInFailure

export interface LoginViewModel {
    readonly serverUrl: string
    readonly setServerUrl: (value: string) => void
    readonly busy: boolean
    readonly error: LoginError | undefined
    /** The API version of the server that was refused (`unsupported-server`; 0 when none), and the one this app speaks. */
    readonly versions: {readonly version: number; readonly supported: number}
    readonly submit: () => void
}

/** Sign-in with GitHub against the server the person typed (Ostrom can be self-hosted). */
export function useLoginViewModel(): LoginViewModel {
    const {signIn, sessionStore, unlock, repositoriesFor} = useServices()
    const [serverUrl, setServerUrlState] = useState(DEFAULT_SERVER_URL)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<LoginError>()
    const [refusedVersion, setRefusedVersion] = useState(0)

    const setServerUrl = useCallback((value: string) => {
        setServerUrlState(value)
        setError(undefined)
    }, [])

    const submit = useCallback(() => {
        const origin = normalizeServerUrl(serverUrl)
        if (origin === undefined) {
            setError("invalid-url")
            return
        }
        setBusy(true)
        setError(undefined)
        void (async () => {
            try {
                const outcome = await signIn.signIn(origin)
                switch (outcome.kind) {
                    case "signed-in": {
                        // The new session reads what the server speaks before anything is kept: a server this app does
                        // not speak gets its token revoked, and nothing of the session stays on the phone.
                        const compatibility = await assessServer(() => repositoriesFor(outcome.session).capabilities.read())
                        if (compatibility.kind === "incompatible") {
                            await repositoriesFor(outcome.session)
                                .account.logout()
                                .catch(() => undefined)
                            setRefusedVersion(compatibility.apiVersion ?? 0)
                            setError(compatibility.apiVersion === undefined ? "server-too-old" : "unsupported-server")
                            break
                        }
                        const session =
                            compatibility.kind === "compatible"
                                ? {...outcome.session, capabilities: compatibility.capabilities}
                                : outcome.session
                        // Without the key kept, the server takes no answer from this phone: it says so then. A sign-in
                        // without a key leaves none of an earlier one behind.
                        // A key that cannot be kept takes no earlier one's place either.
                        await (
                            outcome.unlockKey === undefined ? unlock.forget() : unlock.keep(outcome.unlockKey).catch(() => unlock.forget())
                        ).catch(() => undefined)
                        await sessionStore.getState().signIn(session)
                        break
                    }
                    case "failed":
                        setError(outcome.reason)
                        break
                    case "cancelled":
                        break
                }
            } catch {
                setError("unexpected")
            } finally {
                setBusy(false)
            }
        })()
    }, [serverUrl, signIn, sessionStore, unlock, repositoriesFor])

    return {serverUrl, setServerUrl, busy, error, versions: {version: refusedVersion, supported: SUPPORTED_API_VERSION}, submit}
}
