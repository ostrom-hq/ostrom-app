import * as SecureStore from "expo-secure-store"
import {SecureStorageImpl} from "./SecureStorageImpl"

jest.mock("expo-secure-store", () => {
    const items = new Map<string, string>()
    return {
        WHEN_UNLOCKED_THIS_DEVICE_ONLY: 7,
        getItemAsync: jest.fn((key: string) => Promise.resolve(items.get(key) ?? null)),
        setItemAsync: jest.fn((key: string, value: string) => {
            items.set(key, value)
            return Promise.resolve()
        }),
        deleteItemAsync: jest.fn((key: string) => {
            items.delete(key)
            return Promise.resolve()
        }),
    }
})

describe("SecureStorageImpl", () => {
    it("keeps, reads and forgets a secret, only while the phone is unlocked", async () => {
        const storage = new SecureStorageImpl()
        await expect(storage.get("k")).resolves.toBeUndefined()
        await storage.set("k", "v")
        await expect(storage.get("k")).resolves.toBe("v")
        await storage.remove("k")
        await expect(storage.get("k")).resolves.toBeUndefined()
        expect(SecureStore.setItemAsync).toHaveBeenCalledWith("k", "v", {keychainAccessible: 7})
    })
})
