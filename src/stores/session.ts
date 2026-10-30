import {z} from "zod"
import {createStore, type StoreApi} from "zustand/vanilla"
import {UserSchema} from "@/api/contract/account"
import type {SecureStorage} from "@/services/storage/SecureStorage"

/** What the app keeps between launches: the server, the token, who it belongs to. */
export const SessionSchema = z.object({
    serverUrl: z.url(),
    token: z.string().min(1),
    user: UserSchema,
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
    return createStore<SessionStore>()((set) => ({
        state: {status: "loading"},
        hydrate: async () => {
            let stored: string | undefined
            try {
                stored = await storage.get(SESSION_KEY)
            } catch {
                // The keychain cannot be read (a launch while the phone is locked): sign in again rather than stay blank.
                set({state: {status: "signed-out"}})
                return
            }
            const session = parseStored(stored)
            if (session === undefined) await storage.remove(SESSION_KEY).catch(() => undefined)
            set({state: session === undefined ? {status: "signed-out"} : {status: "signed-in", session}})
        },
        signIn: async (session) => {
            await storage.set(SESSION_KEY, JSON.stringify(session))
            set({state: {status: "signed-in", session}})
        },
        signOut: async () => {
            // Signed out at once, whatever the keychain says. If the token cannot be removed, it is blanked: the next
            // launch reads an unreadable session, which is signed out too.
            set({state: {status: "signed-out"}})
            await storage.remove(SESSION_KEY).catch(() => storage.set(SESSION_KEY, "").catch(() => undefined))
        },
    }))
}
