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
