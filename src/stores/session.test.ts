// SPDX-License-Identifier: AGPL-3.0-only
import {MemoryStorage} from "@/test/fakeServices"
import {createSessionStore, SESSION_KEY, type Session} from "./session"

const session: Session = {serverUrl: "https://ostrom.test", token: "ost_1", user: {login: "rev-b"}}

describe("session store", () => {
    it("starts loading, then signed out when nothing is stored", async () => {
        const store = createSessionStore(new MemoryStorage())
        expect(store.getState().state).toEqual({status: "loading"})
        await store.getState().hydrate()
        expect(store.getState().state).toEqual({status: "signed-out"})
    })

    it("keeps the session across launches, and forgets it on sign-out", async () => {
        const storage = new MemoryStorage()
        await createSessionStore(storage).getState().signIn(session)
        const relaunched = createSessionStore(storage)
        await relaunched.getState().hydrate()
        expect(relaunched.getState().state).toEqual({status: "signed-in", session})
        await relaunched.getState().signOut()
        expect(relaunched.getState().state).toEqual({status: "signed-out"})
        expect(storage.items.has(SESSION_KEY)).toBe(false)
    })

    it("keeps the organization chosen across launches, and chooses nothing signed out", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().chooseOrg("org_acme")
        expect(storage.items.has(SESSION_KEY)).toBe(false)
        await store.getState().signIn(session)
        await store.getState().chooseOrg("org_globex")
        expect(store.getState().state).toEqual({status: "signed-in", session: {...session, orgId: "org_globex"}})
        const relaunched = createSessionStore(storage)
        await relaunched.getState().hydrate()
        expect(relaunched.getState().state).toEqual({status: "signed-in", session: {...session, orgId: "org_globex"}})
    })

    it("lets a sign-out made while the choice is saved win: the token stays out of the keychain", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().signIn(session)
        const save = storage.set.bind(storage)
        let signingOut: Promise<void> | undefined
        storage.set = async (key: string, value: string): Promise<void> => {
            await save(key, value)
            signingOut = store.getState().signOut()
        }
        await store.getState().chooseOrg("org_globex")
        expect(store.getState().state).toEqual({status: "signed-out"})
        await signingOut
        expect(storage.items.has(SESSION_KEY)).toBe(false)
    })

    it("keeps the session signed in while the choice was saved, not the choice", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().signIn(session)
        const other = {...session, token: "ost_2"}
        const save = storage.set.bind(storage)
        let signingIn: Promise<void> | undefined
        storage.set = async (key: string, value: string): Promise<void> => {
            await save(key, value)
            signingIn ??= store.getState().signIn(other)
        }
        await store.getState().chooseOrg("org_globex")
        await signingIn
        expect(store.getState().state).toEqual({status: "signed-in", session: other})
        expect(storage.items.get(SESSION_KEY)).toBe(JSON.stringify(other))
    })

    it("keeps the last of two choices asked one after the other, in memory and in the keychain", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().signIn(session)
        await Promise.all([store.getState().chooseOrg("org_acme"), store.getState().chooseOrg("org_globex")])
        expect(store.getState().state).toEqual({status: "signed-in", session: {...session, orgId: "org_globex"}})
        expect(storage.items.get(SESSION_KEY)).toBe(JSON.stringify({...session, orgId: "org_globex"}))
    })

    it("goes on with the next change after one the keychain refused", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        const save = storage.set.bind(storage)
        let refuse = true
        storage.set = (key: string, value: string): Promise<void> => {
            if (!refuse) return save(key, value)
            refuse = false
            return Promise.reject(new Error("locked"))
        }
        const refused = store.getState().signIn(session)
        const next = store.getState().signIn(session)
        await expect(refused).rejects.toThrow("locked")
        await next
        expect(store.getState().state).toEqual({status: "signed-in", session})
    })

    it("lets a sign-out made while a sign-in is saved win, in memory and in the keychain", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        const save = storage.set.bind(storage)
        let signingOut: Promise<void> | undefined
        storage.set = async (key: string, value: string): Promise<void> => {
            await save(key, value)
            signingOut ??= store.getState().signOut()
        }
        await store.getState().signIn(session)
        expect(store.getState().state).toEqual({status: "signed-out"})
        await signingOut
        expect(store.getState().state).toEqual({status: "signed-out"})
        expect(storage.items.has(SESSION_KEY)).toBe(false)
    })

    it("lets a sign-out made while the session is read at launch win", async () => {
        const storage = new MemoryStorage()
        storage.items.set(SESSION_KEY, JSON.stringify(session))
        const store = createSessionStore(storage)
        const read = storage.get.bind(storage)
        let signingOut: Promise<void> | undefined
        storage.get = async (key: string): Promise<string | undefined> => {
            signingOut = store.getState().signOut()
            return read(key)
        }
        await store.getState().hydrate()
        await signingOut
        expect(store.getState().state).toEqual({status: "signed-out"})
        expect(storage.items.has(SESSION_KEY)).toBe(false)
    })

    it("drops a choice asked before a sign-out and a sign-in: it never lands on the next session", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().signIn(session)
        const other = {...session, token: "ost_2"}
        const choice = store.getState().chooseOrg("org_globex")
        const signingOut = store.getState().signOut()
        const signingIn = store.getState().signIn(other)
        await Promise.all([choice, signingOut, signingIn])
        expect(store.getState().state).toEqual({status: "signed-in", session: other})
        expect(storage.items.get(SESSION_KEY)).toBe(JSON.stringify(other))
    })

    it("blanks the token when removing it throws at once, and signs out even when blanking throws too", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().signIn(session)
        storage.remove = (): Promise<void> => {
            throw new Error("locked")
        }
        await store.getState().signOut()
        expect(storage.items.get(SESSION_KEY)).toBe("")
        storage.set = (): Promise<void> => {
            throw new Error("locked")
        }
        await expect(store.getState().signOut()).resolves.toBeUndefined()
        expect(store.getState().state).toEqual({status: "signed-out"})
    })

    it("opens signed out on an unreadable session even when removing it throws at once", async () => {
        const storage = new MemoryStorage()
        storage.items.set(SESSION_KEY, "not json")
        storage.remove = (): Promise<void> => {
            throw new Error("locked")
        }
        const store = createSessionStore(storage)
        await store.getState().hydrate()
        expect(store.getState().state).toEqual({status: "signed-out"})
    })

    it("keeps the state as it was when a sign-in cannot be written, and a choice asked before it still lands", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().signIn(session)
        const save = storage.set.bind(storage)
        // Only the second sign-in's token is refused.
        storage.set = (key: string, value: string): Promise<void> =>
            value.includes("ost_2") ? Promise.reject(new Error("locked")) : save(key, value)
        const choice = store.getState().chooseOrg("org_globex")
        const other = store.getState().signIn({...session, token: "ost_2"})
        await choice
        await expect(other).rejects.toThrow("locked")
        expect(store.getState().state).toEqual({status: "signed-in", session: {...session, orgId: "org_globex"}})
    })

    it("signs out even when the keychain refuses to remove the token, and blanks it instead", async () => {
        const storage = new MemoryStorage()
        const store = createSessionStore(storage)
        await store.getState().signIn(session)
        storage.remove = (): Promise<void> => Promise.reject(new Error("locked"))
        await store.getState().signOut()
        expect(store.getState().state).toEqual({status: "signed-out"})
        expect(storage.items.get(SESSION_KEY)).toBe("")
        storage.set = (): Promise<void> => Promise.reject(new Error("locked"))
        await expect(store.getState().signOut()).resolves.toBeUndefined()
    })

    it("opens signed out when the keychain cannot be read", async () => {
        const storage = new MemoryStorage()
        storage.get = (): Promise<string | undefined> => Promise.reject(new Error("locked"))
        const store = createSessionStore(storage)
        await store.getState().hydrate()
        expect(store.getState().state).toEqual({status: "signed-out"})
    })

    it("still opens signed out when an unreadable session cannot be removed", async () => {
        const storage = new MemoryStorage()
        storage.items.set(SESSION_KEY, "not json")
        storage.remove = (): Promise<void> => Promise.reject(new Error("locked"))
        const store = createSessionStore(storage)
        await store.getState().hydrate()
        expect(store.getState().state).toEqual({status: "signed-out"})
    })

    it.each(["not json", JSON.stringify({token: "x"})])("drops an unreadable stored session: %s", async (stored) => {
        const storage = new MemoryStorage()
        storage.items.set(SESSION_KEY, stored)
        const store = createSessionStore(storage)
        await store.getState().hydrate()
        expect(store.getState().state).toEqual({status: "signed-out"})
        expect(storage.items.has(SESSION_KEY)).toBe(false)
    })
})
