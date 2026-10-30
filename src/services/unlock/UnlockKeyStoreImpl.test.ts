// SPDX-License-Identifier: AGPL-3.0-only
import * as SecureStore from "expo-secure-store"
import {MemoryStorage} from "@/test/fakeServices"
import {UNLOCK_KEY, UNLOCK_KEY_KEPT, UnlockKeyStoreImpl} from "./UnlockKeyStoreImpl"

jest.mock("expo-secure-store", () => ({
    WHEN_PASSCODE_SET_THIS_DEVICE_ONLY: 6,
    setItemAsync: jest.fn(() => Promise.resolve()),
    getItemAsync: jest.fn(() => Promise.resolve("key-1")),
    deleteItemAsync: jest.fn(() => Promise.resolve()),
}))

const getItem = jest.mocked(SecureStore.getItemAsync)

describe("UnlockKeyStoreImpl", () => {
    it("keeps the key behind the biometrics, and knows it is there without asking them", async () => {
        const plain = new MemoryStorage()
        const keys = new UnlockKeyStoreImpl(plain)
        expect(await keys.has()).toBe(false)
        expect(await keys.read("Approve")).toEqual({kind: "none"})
        expect(getItem).not.toHaveBeenCalled()
        await keys.save("key-1")
        expect(SecureStore.setItemAsync).toHaveBeenCalledWith(UNLOCK_KEY, "key-1", {
            keychainAccessible: 6,
            requireAuthentication: true,
        })
        expect(plain.items.get(UNLOCK_KEY_KEPT)).toBe("1")
        expect(await keys.has()).toBe(true)
    })

    it("gives the key back through the biometric prompt", async () => {
        const plain = new MemoryStorage()
        const keys = new UnlockKeyStoreImpl(plain)
        await keys.save("key-1")
        expect(await keys.read("Approve")).toEqual({kind: "key", key: "key-1"})
        expect(getItem).toHaveBeenLastCalledWith(UNLOCK_KEY, {
            keychainAccessible: 6,
            requireAuthentication: true,
            authenticationPrompt: "Approve",
        })
        getItem.mockResolvedValueOnce(null)
        expect(await keys.read("Approve")).toEqual({kind: "none"})
    })

    it.each([
        [new Error("User canceled the operation"), "cancelled"],
        [new Error("Biometry is locked out"), "failed"],
        ["not an error", "failed"],
    ])("reads a refused prompt (%p) as %s", async (error, kind) => {
        const keys = new UnlockKeyStoreImpl(new MemoryStorage())
        await keys.save("key-1")
        getItem.mockRejectedValueOnce(error)
        expect(await keys.read("Approve")).toEqual({kind})
    })

    it("removes the key and its mark", async () => {
        const plain = new MemoryStorage()
        const keys = new UnlockKeyStoreImpl(plain)
        await keys.save("key-1")
        await keys.clear()
        expect(plain.items.has(UNLOCK_KEY_KEPT)).toBe(false)
        expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith(UNLOCK_KEY, {keychainAccessible: 6, requireAuthentication: true})
    })
})
