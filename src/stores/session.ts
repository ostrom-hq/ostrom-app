// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"
import {createStore, type StoreApi} from "zustand/vanilla"
import {UserSchema} from "@/api/contract/account"
import type {SecureStorage} from "@/services/storage/SecureStorage"

/**
 * What the app keeps between launches: the server, the token, who it belongs to, and the organization the calls act
 * in (`Ostrom-Org`), when the server named one at sign-in. A session kept before it existed has none.
 */
export const SessionSchema = z.object({
    serverUrl: z.url(),
    token: z.string().min(1),
    user: UserSchema,
    orgId: z.string().min(1).optional(),
})
export type Session = z.output<typeof SessionSchema>

export type SessionState =
    | {readonly status: "loading"}
    | {readonly status: "signed-out"}
    | {readonly status: "signed-in"; readonly session: Session}

export interface SessionStore {
    readonly state: SessionState
    /**
     * Reads the stored session at launch; a missing or unreadable one means signed out. Once the person has signed in or
     * out, it changes nothing, in memory or in the keychain.
     */
    hydrate(): Promise<void>
    signIn(session: Session): Promise<void>
    /** Keeps the organization the calls act in from now on; nothing happens signed out. */
    chooseOrg(orgId: string): Promise<void>
    signOut(): Promise<void>
}

export const SESSION_KEY = "ostrom.session"

export interface SessionStoreOptions {
    /**
     * Whether this build talks to the server of a session kept by an earlier one. A store build keeps an HTTPS server
     * only (AUDIT-SECU-APP-2 A2): a session a development build kept on `http://` is dropped, not used in clear.
     * Every server by default.
     */
    readonly keepsServer?: (serverUrl: string) => boolean
}

function keepsEveryServer(): boolean {
    return true
}

function parseStored(text: string | undefined, keepsServer: (serverUrl: string) => boolean): Session | undefined {
    if (text === undefined) return undefined
    try {
        const parsed = SessionSchema.safeParse(JSON.parse(text))
        return parsed.success && keepsServer(parsed.data.serverUrl) ? parsed.data : undefined
    } catch {
        return undefined
    }
}

/** The session, kept in the secure storage. Client state only: nothing from the server is copied here. */
export function createSessionStore(
    storage: SecureStorage,
    {keepsServer = keepsEveryServer}: SessionStoreOptions = {},
): StoreApi<SessionStore> {
    // The keychain is written one change at a time, in the order they were asked: the last asked is the one kept.
    let tail: Promise<unknown> = Promise.resolve()
    function inTurn<T>(change: () => Promise<T>): Promise<T> {
        const result = tail.then(change)
        tail = result.catch(() => undefined)
        return result
    }
    // A sign-out is the only change that shows at once, outside the turns: a change asked before it never shows after.
    let signOuts = 0
    // A keychain write whose failure is ignored, even when it throws at once.
    const quietly = (write: () => Promise<void>): Promise<void> => Promise.resolve().then(write).catch(() => undefined)
    return createStore<SessionStore>()((set, get) => ({
        state: {status: "loading"},
        // Only the launch is decided here: once the person has signed in or out, a reading of the keychain changes nothing.
        hydrate: () =>
            inTurn(async () => {
                let stored: string | undefined
                try {
                    stored = await storage.get(SESSION_KEY)
                } catch {
                    // The keychain cannot be read (a launch while the phone is locked): sign in again rather than stay blank.
                    if (get().state.status === "loading") set({state: {status: "signed-out"}})
                    return
                }
                if (get().state.status !== "loading") return
                // Unreadable, or on a server this build does not keep: forgotten, and signed out.
                const session = parseStored(stored, keepsServer)
                if (session === undefined) await quietly(() => storage.remove(SESSION_KEY))
                set({state: session === undefined ? {status: "signed-out"} : {status: "signed-in", session}})
            }),
        signIn: (session) => {
            const asked = signOuts
            return inTurn(async () => {
                await storage.set(SESSION_KEY, JSON.stringify(session))
                if (signOuts === asked) set({state: {status: "signed-in", session}})
            })
        },
        chooseOrg: (orgId) => {
            const asked = signOuts
            return inTurn(async () => {
                const {state} = get()
                if (signOuts !== asked || state.status !== "signed-in") return
                const session = {...state.session, orgId}
                await storage.set(SESSION_KEY, JSON.stringify(session))
                // Signed out while it was saved: that wins, and its removal, queued behind this write, clears the keychain.
                if (signOuts === asked) set({state: {status: "signed-in", session}})
            })
        },
        signOut: () => {
            // Signed out at once, whatever the keychain says. If the token cannot be removed, it is blanked: the next
            // launch reads an unreadable session, which is signed out too.
            signOuts += 1
            set({state: {status: "signed-out"}})
            return inTurn(async () => {
                try {
                    await storage.remove(SESSION_KEY)
                } catch {
                    await quietly(() => storage.set(SESSION_KEY, ""))
                }
            })
        },
    }))
}
