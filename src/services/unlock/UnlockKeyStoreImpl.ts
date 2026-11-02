// SPDX-License-Identifier: AGPL-3.0-only
import * as SecureStore from "expo-secure-store"
import type {SecureStorage} from "@/services/storage/SecureStorage"
import type {UnlockKeyRead, UnlockKeyStore} from "./UnlockKeyStore"

export const UNLOCK_KEY = "ostrom.unlock-key"
/** Kept without biometrics, so that knowing whether a key exists never prompts. */
export const UNLOCK_KEY_KEPT = "ostrom.unlock-key.kept"

/** Behind the biometrics, readable only on this phone, and gone if the passcode is removed. */
const GUARDED: SecureStore.SecureStoreOptions = {
    keychainAccessible: SecureStore.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY,
    requireAuthentication: true,
}

export class UnlockKeyStoreImpl implements UnlockKeyStore {
    constructor(private readonly plain: SecureStorage) {}

    async save(key: string): Promise<void> {
        await SecureStore.setItemAsync(UNLOCK_KEY, key, GUARDED)
        await this.plain.set(UNLOCK_KEY_KEPT, "1")
    }

    async has(): Promise<boolean> {
        return (await this.plain.get(UNLOCK_KEY_KEPT)) === "1"
    }

    async read(prompt: string): Promise<UnlockKeyRead> {
        if (!(await this.has())) return {kind: "none"}
        try {
            const key = await SecureStore.getItemAsync(UNLOCK_KEY, {...GUARDED, authenticationPrompt: prompt})
            return key === null ? {kind: "none"} : {kind: "key", key}
        } catch (error) {
            // expo-secure-store says a dismissed prompt only in its message.
            return error instanceof Error && /cancel/i.test(error.message) ? {kind: "cancelled"} : {kind: "failed"}
        }
    }

    async clear(): Promise<void> {
        // The key first: if it cannot be deleted, the mark stays and the next sign-out tries again.
        await SecureStore.deleteItemAsync(UNLOCK_KEY, GUARDED)
        await this.plain.remove(UNLOCK_KEY_KEPT)
    }
}
