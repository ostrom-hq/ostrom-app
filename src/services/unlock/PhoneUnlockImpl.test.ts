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
    const unlock = new PhoneUnlockImpl({keys, biometrics: {confirm}, now: () => clock.now})
    return {unlock, keys, confirm, account, serverUnlock, clock}
}

describe("PhoneUnlockImpl", () => {
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

    it.each([400, 403])("says refused when the server answers %p to the key, and unlocks again next time", async (status) => {
        const {unlock, account, serverUnlock} = harness(() => Promise.reject(new ApiError({kind: "refused", status, type: "unlock_refused", message: ""})))
        await unlock.keep("key-1")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("refused")
        expect(await unlock.unlock("Approve", account, {always: false})).toBe("refused")
        expect(serverUnlock).toHaveBeenCalledTimes(2)
    })

    it.each<[string, unknown]>([
        ["offline", new ApiError({kind: "network"})],
        ["a 5xx", new ApiError({kind: "server", status: 502})],
        ["an expired session", new ApiError({kind: "unauthorized"})],
        ["another refusal", new ApiError({kind: "refused", status: 409, type: "conflict", message: ""})],
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
        const clear = jest.fn(() => Promise.resolve())
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
