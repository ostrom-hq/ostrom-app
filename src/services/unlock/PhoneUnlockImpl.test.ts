// SPDX-License-Identifier: AGPL-3.0-only
import type {Unlock} from "@/api/contract/account"
import {ApiError} from "@/api/http/ApiError"
import type {AccountRepository} from "@/repositories/AccountRepository"
import type {BiometricOutcome} from "@/services/biometrics/BiometricGate"
import {MemoryUnlockKeyStore} from "./MemoryUnlockKeyStore"
import {FRESH_UNLOCK_MS, PhoneUnlockImpl} from "./PhoneUnlockImpl"
import type {UnlockKeyRead, UnlockKeyStore} from "./UnlockKeyStore"

interface Harness {
    readonly unlock: PhoneUnlockImpl
    readonly keys: MemoryUnlockKeyStore
    readonly confirm: jest.Mock<Promise<BiometricOutcome>, [string]>
    readonly account: AccountRepository
    readonly serverUnlock: jest.Mock<Promise<Unlock>, [string]>
    readonly clock: {now: number}
}

function harness(server: (key: string) => Promise<Unlock> = () => Promise.resolve({authenticatedAt: "2026-10-30T12:00:00Z"})): Harness {
    const keys = new MemoryUnlockKeyStore()
    const confirm = jest.fn((_prompt: string) => Promise.resolve<BiometricOutcome>("confirmed"))
    const serverUnlock = jest.fn(server)
    const clock = {now: 1_000_000}
    const account: AccountRepository = {
        me: jest.fn(),
        organizations: jest.fn(),
        exchangeCode: jest.fn(),
        unlock: serverUnlock,
        logout: jest.fn(),
    }
    const unlock = new PhoneUnlockImpl({keys, biometrics: {confirm}, now: () => clock.now, leaving: (listener) => leaving.push(listener)})
    return {unlock, keys, confirm, account, serverUnlock, clock}
}

/** What the unlock asked to hear when the app goes to the background; `leave` plays it. */
let leaving: (() => void)[] = []

function leave(): void {
    for (const listener of leaving) listener()
}

describe("PhoneUnlockImpl", () => {
    beforeEach(() => {
        leaving = []
    })

    it("asks the biometrics again for a low-risk answer once the app has been left (AUDIT-SECU-APP-2 N4)", async () => {
        const {unlock, keys, account, clock} = harness()
        await unlock.keep("key-1")
        const read = jest.spyOn(keys, "read")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("confirmed")
        clock.now += 1_000
        // The phone left in another hand, the app in the background: the fresh unlock was for the person who left it.
        leave()
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("confirmed")
        expect(read).toHaveBeenCalledTimes(2)
    })

    it("without a key, asks the biometrics when the answer needs them, and nothing for a low-risk one", async () => {
        const {unlock, confirm, account, serverUnlock} = harness()
        confirm.mockResolvedValueOnce("cancelled")
        expect(await unlock.unlock("Approve", account, {always: true})).toBe("cancelled")
        expect(confirm).toHaveBeenCalledWith("Approve")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("confirmed")
        expect(confirm).toHaveBeenCalledTimes(1)
        expect(serverUnlock).not.toHaveBeenCalled()
    })

    it("with a key, proves the biometrics to the server, then takes a low-risk answer for a while without asking", async () => {
        const {unlock, keys, confirm, account, serverUnlock, clock} = harness()
        await unlock.keep("key-1")
        const read = jest.spyOn(keys, "read")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("confirmed")
        expect(serverUnlock).toHaveBeenCalledWith("key-1")
        clock.now += FRESH_UNLOCK_MS - 1
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("confirmed")
        expect(read).toHaveBeenCalledTimes(1)
        // An answer that needs the biometrics asks them again, however recent the unlock.
        expect(await unlock.unlock("Approve", account, {always: true})).toBe("confirmed")
        expect(read).toHaveBeenCalledTimes(2)
        clock.now += FRESH_UNLOCK_MS
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("confirmed")
        expect(read).toHaveBeenCalledTimes(3)
        expect(confirm).not.toHaveBeenCalled()
    })

    it.each([
        new ApiError({kind: "refused", status: 400, type: "unlock_refused", message: ""}),
        new ApiError({kind: "refused", status: 403, type: "unlock_refused", message: ""}),
    ])("says refused when the server answers %p to the key, and unlocks again next time", async (refusal) => {
        const {unlock, account, serverUnlock} = harness(() => Promise.reject(refusal))
        await unlock.keep("key-1")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("refused")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("refused")
        expect(serverUnlock).toHaveBeenCalledTimes(2)
    })

    it.each<[string, Error]>([
        ["offline", new ApiError({kind: "network"})],
        ["a 5xx", new ApiError({kind: "server", status: 502})],
        ["an expired session", new ApiError({kind: "unauthorized"})],
        ["another refusal", new ApiError({kind: "refused", status: 409, type: "conflict", message: ""})],
        ["another status", new ApiError({kind: "unexpected-status", status: 404})],
        ["a proxy's bare 403", new ApiError({kind: "unexpected-status", status: 403})],
        ["not the server's", new Error("boom")],
    ])("lets the failure through when the server is %s: the key is not refused", async (_case, error) => {
        const {unlock, keys, account} = harness(() => Promise.reject(error))
        await unlock.keep("key-1")
        await expect(unlock.unlock("Approve", account, {always: false})).rejects.toBe(error)
        expect(keys.key).toBe("key-1")
    })

    it.each<[UnlockKeyRead, boolean, string]>([
        [{kind: "cancelled"}, true, "cancelled"],
        [{kind: "failed"}, true, "failed"],
    ])("reads %p from the key store (always: %p): %s", async (read, always, expected) => {
        const keys: UnlockKeyStore = {
            save: jest.fn(),
            has: () => Promise.resolve(true),
            read: () => Promise.resolve(read),
            clear: jest.fn(),
        }
        const confirm = jest.fn((_prompt: string) => Promise.resolve<BiometricOutcome>("unavailable"))
        const unlock = new PhoneUnlockImpl({keys, biometrics: {confirm}, now: () => 0})
        expect(await unlock.unlock("Approve", harness().account, {always})).toBe(expected)
    })

    it.each([true, false])("says refused and clears the mark when the kept key is gone from the keychain (always: %p)", async (always) => {
        // Even a mark that cannot be cleared says refused.
        const clear = jest.fn(() => Promise.reject(new Error("keychain locked")))
        const keys: UnlockKeyStore = {
            save: jest.fn(),
            has: () => Promise.resolve(true),
            read: () => Promise.resolve({kind: "none"}),
            clear,
        }
        const confirm = jest.fn((_prompt: string) => Promise.resolve<BiometricOutcome>("confirmed"))
        const unlock = new PhoneUnlockImpl({keys, biometrics: {confirm}, now: () => 0})
        expect(await unlock.unlock("Approve", harness().account, {always})).toBe("refused")
        expect(clear).toHaveBeenCalledTimes(1)
        expect(confirm).not.toHaveBeenCalled()
    })

    it("proves nothing for the next session when the unlock answers after a sign-out", async () => {
        const unlocked: Unlock = {authenticatedAt: "2026-10-30T12:00:00Z"}
        let answer = (): void => undefined
        let reach = (): void => undefined
        const reached = new Promise<void>((resolve) => {
            reach = resolve
        })
        const {unlock, account, serverUnlock} = harness((key) =>
            key === "key-1"
                ? new Promise((resolve) => {
                      answer = () => {
                          resolve(unlocked)
                      }
                      reach()
                  })
                : Promise.resolve(unlocked),
        )
        await unlock.keep("key-1")
        const pending = unlock.unlock("Approve", account, {always: false})
        await reached
        await unlock.forget()
        await unlock.keep("key-2")
        answer()
        // No answer leaves on an unlock of the session that ended.
        expect(await pending).toBe("cancelled")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("confirmed")
        expect(serverUnlock).toHaveBeenLastCalledWith("key-2")
    })

    it("leaves the next session's key alone when a read of the last one finds nothing after a sign-out", async () => {
        let found = (): void => undefined
        let reach = (): void => undefined
        const reached = new Promise<void>((resolve) => {
            reach = resolve
        })
        const clear = jest.fn(() => Promise.resolve())
        const keys: UnlockKeyStore = {
            save: () => Promise.resolve(),
            has: () => Promise.resolve(true),
            read: () =>
                new Promise((resolve) => {
                    found = () => {
                        resolve({kind: "none"})
                    }
                    reach()
                }),
            clear,
        }
        const unlock = new PhoneUnlockImpl({keys, biometrics: {confirm: jest.fn()}, now: () => 0})
        const pending = unlock.unlock("Approve", harness().account, {always: false})
        await reached
        await unlock.keep("key-2")
        found()
        expect(await pending).toBe("refused")
        expect(clear).not.toHaveBeenCalled()
    })

    it("starts afresh with each key kept: an unlock of the last session is not fresh for the next", async () => {
        const {unlock, account, serverUnlock} = harness()
        await unlock.keep("key-1")
        await unlock.unlock("Approve", account, {always: false})
        await unlock.keep("key-2")
        await unlock.unlock("Approve", account, {always: false})
        expect(serverUnlock).toHaveBeenLastCalledWith("key-2")
        expect(serverUnlock).toHaveBeenCalledTimes(2)
    })

    it("keeps time on the phone's clock by default", async () => {
        const {keys, account, serverUnlock} = harness()
        const unlock = new PhoneUnlockImpl({keys, biometrics: {confirm: jest.fn()}})
        await unlock.keep("key-1")
        await unlock.unlock("Approve", account, {always: false})
        await unlock.unlock("Approve", account, {always: false})
        expect(serverUnlock).toHaveBeenCalledTimes(1)
    })

    it("gives nothing back from an empty memory store", async () => {
        expect(await new MemoryUnlockKeyStore().read()).toEqual({kind: "none"})
    })

    it("forgets the key and the last unlock at sign-out", async () => {
        const {unlock, keys, account, serverUnlock} = harness()
        await unlock.keep("key-1")
        await unlock.unlock("Approve", account, {always: true})
        await unlock.forget()
        expect(keys.key).toBeUndefined()
        await unlock.keep("key-2")
        await unlock.unlock("Approve", account, {always: false})
        expect(serverUnlock).toHaveBeenLastCalledWith("key-2")
    })
})
