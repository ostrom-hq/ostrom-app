// SPDX-License-Identifier: AGPL-3.0-only
import type {UnlockKeyRead, UnlockKeyStore} from "./UnlockKeyStore"

/** The unlock key in memory, given back without a prompt: for tests and the E2E build, never a store build. */
export class MemoryUnlockKeyStore implements UnlockKeyStore {
    key: string | undefined

    save(key: string): Promise<void> {
        this.key = key
        return Promise.resolve()
    }

    has(): Promise<boolean> {
        return Promise.resolve(this.key !== undefined)
    }

    read(): Promise<UnlockKeyRead> {
        return Promise.resolve(this.key === undefined ? {kind: "none"} : {kind: "key", key: this.key})
    }

    clear(): Promise<void> {
        this.key = undefined
        return Promise.resolve()
    }
}
