// SPDX-License-Identifier: AGPL-3.0-only
import * as SecureStore from "expo-secure-store"
import type {SecureStorage} from "./SecureStorage"

/** Only readable while the phone is unlocked, and never restored on another device from a backup. */
const OPTIONS: SecureStore.SecureStoreOptions = {keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY}

export class SecureStorageImpl implements SecureStorage {
    async get(key: string): Promise<string | undefined> {
        return (await SecureStore.getItemAsync(key, OPTIONS)) ?? undefined
    }

    set(key: string, value: string): Promise<void> {
        return SecureStore.setItemAsync(key, value, OPTIONS)
    }

    remove(key: string): Promise<void> {
        return SecureStore.deleteItemAsync(key, OPTIONS)
    }
}
