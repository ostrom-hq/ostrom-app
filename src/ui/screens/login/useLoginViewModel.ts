// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useState} from "react"
import {useServices} from "@/services/AppServices"
import type {SignInFailure} from "@/services/signIn/GithubSignIn"
import {DEFAULT_SERVER_URL} from "@/config/env"
import {normalizeServerUrl} from "./serverUrl"

/** `unexpected`: the browser or the keychain failed outside the sign-in itself. */
export type LoginError = "invalid-url" | "unexpected" | SignInFailure

export interface LoginViewModel {
    readonly serverUrl: string
    readonly setServerUrl: (value: string) => void
    readonly busy: boolean
    readonly error: LoginError | undefined
    readonly submit: () => void
}

/** Sign-in with GitHub against the server the person typed (Ostrom can be self-hosted). */
export function useLoginViewModel(): LoginViewModel {
    const {signIn, sessionStore, unlock} = useServices()
    const [serverUrl, setServerUrlState] = useState(DEFAULT_SERVER_URL)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<LoginError>()

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
                    case "signed-in":
                        // Without the key kept, the server takes no answer from this phone: it says so then. A sign-in
                        // without a key leaves none of an earlier one behind.
                        // A key that cannot be kept takes no earlier one's place either.
                        await (
                            outcome.unlockKey === undefined ? unlock.forget() : unlock.keep(outcome.unlockKey).catch(() => unlock.forget())
                        ).catch(() => undefined)
                        await sessionStore.getState().signIn(outcome.session)
                        break
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
    }, [serverUrl, signIn, sessionStore, unlock])

    return {serverUrl, setServerUrl, busy, error, submit}
}
