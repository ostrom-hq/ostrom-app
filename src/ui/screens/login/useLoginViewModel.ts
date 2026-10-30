import {useCallback, useState} from "react"
import {useServices} from "@/services/AppServices"
import type {SignInFailure} from "@/services/signIn/GithubSignIn"
import {DEFAULT_SERVER_URL} from "@/config/env"
import {normalizeServerUrl} from "./serverUrl"

export type LoginError = "invalid-url" | SignInFailure

export interface LoginViewModel {
    readonly serverUrl: string
    readonly setServerUrl: (value: string) => void
    readonly busy: boolean
    readonly error: LoginError | undefined
    readonly submit: () => void
}

/** Sign-in with GitHub against the server the person typed (Ostrom can be self-hosted). */
export function useLoginViewModel(): LoginViewModel {
    const {signIn, sessionStore} = useServices()
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
            const outcome = await signIn.signIn(origin)
            switch (outcome.kind) {
                case "signed-in":
                    await sessionStore.getState().signIn(outcome.session)
                    break
                case "failed":
                    setError(outcome.reason)
                    break
                case "cancelled":
                    break
            }
            setBusy(false)
        })()
    }, [serverUrl, signIn, sessionStore])

    return {serverUrl, setServerUrl, busy, error, submit}
}
