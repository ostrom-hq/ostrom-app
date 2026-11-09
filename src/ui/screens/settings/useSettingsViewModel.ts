// SPDX-License-Identifier: AGPL-3.0-only
import {useCallback, useState} from "react"
import type {NotificationSettings, QuietHours} from "@/api/contract/notifications"
import {useDeviceRegistration, useNotificationSettings, useNotificationSettingsEditor} from "@/query/notifications"
import {FEATURES} from "@/domain/capabilities/compatibility"
import {useCapabilities, useFeature, useRepositories, useServices, useSessionState} from "@/services/AppServices"
import {isPresent} from "@/ui/format/presence"

export type Toggle = "requests" | "decisions" | "spending"

/** The default quiet hours, in the phone's time zone, when the person turns them on. */
export function defaultQuietHours(timeZone: string): QuietHours {
    return {start: "22:00", end: "07:00", timeZone}
}

export type SettingsState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {readonly kind: "ready"; readonly settings: NotificationSettings}

export interface SettingsViewModel {
    readonly login: string | undefined
    readonly serverUrl: string | undefined
    /** The server's release and the API version it speaks; undefined until they have been read. */
    readonly serverVersion: string | undefined
    readonly apiVersion: number | undefined
    readonly appVersion: string | undefined
    /** Whether the server serves notification preferences; the section is hidden otherwise. */
    readonly notificationsServed: boolean
    /** Whether this phone is registered for push; undefined while it is being asked. */
    readonly pushOn: boolean | undefined
    readonly state: SettingsState
    readonly saveFailed: boolean
    readonly toggle: (key: Toggle, value: boolean) => void
    readonly setQuietHours: (on: boolean) => void
    readonly retry: () => void
    readonly signingOut: boolean
    readonly signOut: () => void
}

export function useSettingsViewModel(locale: string, timeZone: string, appVersion: string | undefined): SettingsViewModel {
    const session = useSessionState()
    const {sessionStore} = useServices()
    const {account, notifications} = useRepositories()
    const query = useNotificationSettings()
    const capabilities = useCapabilities()
    const notificationsServed = useFeature(FEATURES.notifications)
    const editor = useNotificationSettingsEditor()
    const [signingOut, setSigningOut] = useState(false)
    const device = useDeviceRegistration(locale).data
    const settings = query.data
    const {change} = editor

    const toggle = useCallback(
        (key: Toggle, value: boolean) => {
            change((current) => ({...current, [key]: value}))
        },
        [change],
    )
    const setQuietHours = useCallback(
        (on: boolean) => {
            // Off is the field left out: the contract has no `null` for it.
            change(({requests, decisions, spending}) =>
                on
                    ? {requests, decisions, spending, quietHours: defaultQuietHours(timeZone)}
                    : {requests, decisions, spending},
            )
        },
        [change, timeZone],
    )
    const signOut = useCallback(() => {
        setSigningOut(true)
        // Best effort, never waited for: this phone stops taking push, then the token is revoked on the server (in this
        // order, the revocation would refuse the first). The keychain is cleared at once, reachable server or not.
        void (async () => {
            if (isPresent(device)) await notifications.unregisterDevice(device.id).catch(() => undefined)
            await account.logout().catch(() => undefined)
        })()
        // The unlock key goes with the session, on this sign-out as on any other (`AppRoot`).
        void sessionStore.getState().signOut()
    }, [device, notifications, account, sessionStore])

    const signedIn = session.status === "signed-in" ? session.session : undefined
    const state: SettingsState =
        settings !== undefined ? {kind: "ready", settings} : query.isError ? {kind: "error"} : {kind: "loading"}
    return {
        login: signedIn?.user.login,
        serverUrl: signedIn?.serverUrl,
        serverVersion: capabilities?.serverVersion,
        apiVersion: capabilities?.apiVersion,
        appVersion,
        notificationsServed,
        pushOn: device === undefined ? undefined : device !== null,
        state,
        saveFailed: editor.failed,
        toggle,
        setQuietHours,
        retry: () => {
            void query.refetch()
        },
        signingOut,
        signOut,
    }
}
