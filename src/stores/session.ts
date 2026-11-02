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
    /** Reads the stored session at launch; a missing or unreadable one means signed out. */
    hydrate(): Promise<void>
    signIn(session: Session): Promise<void>
    /** Keeps the organization the calls act in from now on; nothing happens signed out. */
    chooseOrg(orgId: string): Promise<void>
    signOut(): Promise<void>
}

export const SESSION_KEY = "ostrom.session"

function parseStored(text: string | undefined): Session | undefined {
    if (text === undefined) return undefined
    try {
        const parsed = SessionSchema.safeParse(JSON.parse(text))
        return parsed.success ? parsed.data : undefined
    } catch {
        return undefined
    }
}

/** The session, kept in the secure storage. Client state only: nothing from the server is copied here. */
export function createSessionStore(storage: SecureStorage): StoreApi<SessionStore> {
    // The keychain is written one change at a time, in the order they were asked: the last asked is the one kept.
    let tail: Promise<unknown> = Promise.resolve()
    function inTurn<T>(change: () => Promise<T>): Promise<T> {
        const result = tail.then(change)
        tail = result.catch(() => undefined)
        return result
    }
    // Each sign-in and sign-out starts anew: a change asked before one never shows after it.
    let epoch = 0
    return createStore<SessionStore>()((set, get) => ({
        state: {status: "loading"},
        hydrate: () => {
            const asked = epoch
            return inTurn(async () => {
                let stored: string | undefined
                try {
                    stored = await storage.get(SESSION_KEY)
                } catch {
                    // The keychain cannot be read (a launch while the phone is locked): sign in again rather than stay blank.
                    if (epoch === asked) set({state: {status: "signed-out"}})
                    return
                }
                const session = parseStored(stored)
                if (session === undefined) await storage.remove(SESSION_KEY).catch(() => undefined)
                if (epoch === asked) set({state: session === undefined ? {status: "signed-out"} : {status: "signed-in", session}})
            })
        },
        signIn: (session) => {
            epoch += 1
            const asked = epoch
            return inTurn(async () => {
                await storage.set(SESSION_KEY, JSON.stringify(session))
                if (epoch === asked) set({state: {status: "signed-in", session}})
            })
        },
        chooseOrg: (orgId) => {
            const asked = epoch
            return inTurn(async () => {
                const {state} = get()
                if (epoch !== asked || state.status !== "signed-in") return
                const session = {...state.session, orgId}
                await storage.set(SESSION_KEY, JSON.stringify(session))
                // Signed out or in again while it was saved: that wins, and its own write, queued behind this one, follows.
                if (epoch === asked) set({state: {status: "signed-in", session}})
            })
        },
        signOut: () => {
            // Signed out at once, whatever the keychain says. If the token cannot be removed, it is blanked: the next
            // launch reads an unreadable session, which is signed out too.
            epoch += 1
            set({state: {status: "signed-out"}})
            return inTurn(async () => {
                try {
                    await storage.remove(SESSION_KEY)
                } catch {
                    await storage.set(SESSION_KEY, "").catch(() => undefined)
                }
            })
        },
    }))
}
