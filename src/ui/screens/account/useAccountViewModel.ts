// SPDX-License-Identifier: AGPL-3.0-only
import {useState} from "react"
import type {Account} from "@/api/contract/account"
import {useMyAccount} from "@/query/account"
import {useServices, useSessionState} from "@/services/AppServices"

/** How a person signs in with GitHub, in the account's `providers`. */
const GITHUB = "github"

export type AccountState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {readonly kind: "ready"; readonly account: Account; readonly githubLinked: boolean}

export interface AccountViewModel {
    readonly state: AccountState
    readonly retry: () => void
    /**
     * Opens the person's account in the console (`/account`): linking GitHub, the browser sessions and the CLI's
     * workstations need a browser session, which the app's token is not.
     */
    readonly openConsole: () => void
    /** The console did not open: its address, to copy and open by hand. */
    readonly consoleLink: {readonly url: string; readonly copied: boolean} | undefined
    readonly copyConsoleLink: () => void
}

export function useAccountViewModel(): AccountViewModel {
    const query = useMyAccount()
    const session = useSessionState()
    const {openUrl, copyText} = useServices()
    const [consoleLink, setConsoleLink] = useState<{url: string; copied: boolean}>()
    const account = query.data
    const state: AccountState =
        account !== undefined
            ? {kind: "ready", account, githubLinked: account.providers.includes(GITHUB)}
            : query.isError
              ? {kind: "error"}
              : {kind: "loading"}
    return {
        state,
        retry: () => {
            void query.refetch()
        },
        openConsole: () => {
            if (session.status !== "signed-in") return
            const url = `${session.session.serverUrl}/account`
            void openUrl(url).then(
                () => {
                    setConsoleLink(undefined)
                },
                () => {
                    setConsoleLink({url, copied: false})
                },
            )
        },
        consoleLink,
        copyConsoleLink: () => {
            if (consoleLink === undefined) return
            const {url} = consoleLink
            void copyText(url).then(
                () => {
                    setConsoleLink({url, copied: true})
                },
                () => {
                    setConsoleLink({url, copied: false})
                },
            )
        },
    }
}
