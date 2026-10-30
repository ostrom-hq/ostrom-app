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

    it.each(["not json", JSON.stringify({token: "x"})])("drops an unreadable stored session: %s", async (stored) => {
        const storage = new MemoryStorage()
        storage.items.set(SESSION_KEY, stored)
        const store = createSessionStore(storage)
        await store.getState().hydrate()
        expect(store.getState().state).toEqual({status: "signed-out"})
        expect(storage.items.has(SESSION_KEY)).toBe(false)
    })
})
