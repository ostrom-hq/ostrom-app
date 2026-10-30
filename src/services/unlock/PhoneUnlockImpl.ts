// SPDX-License-Identifier: AGPL-3.0-only
import type {AccountRepository} from "@/repositories/AccountRepository"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import type {PhoneUnlock, UnlockOptions, UnlockOutcome} from "./PhoneUnlock"
import type {UnlockKeyStore} from "./UnlockKeyStore"

/** The server takes an answer 5 minutes after an unlock; the app unlocks again one minute before. */
export const FRESH_UNLOCK_MS = 4 * 60 * 1000

export interface PhoneUnlockDependencies {
    readonly keys: UnlockKeyStore
    readonly biometrics: BiometricGate
    /** The clock, in milliseconds; the phone's by default. */
    readonly now?: () => number
}

function phoneNow(): number {
    return Date.now()
}

export class PhoneUnlockImpl implements PhoneUnlock {
    private unlockedAt: number | undefined

    constructor(private readonly dependencies: PhoneUnlockDependencies) {}

    keep(unlockKey: string): Promise<void> {
        return this.dependencies.keys.save(unlockKey)
    }

    async unlock(prompt: string, account: AccountRepository, {always}: UnlockOptions): Promise<UnlockOutcome> {
        const {keys, biometrics, now = phoneNow} = this.dependencies
        // No key (a server before the unlock, or a session kept before it): the app's own check, as before.
        const withoutKey = (): Promise<UnlockOutcome> => (always ? biometrics.confirm(prompt) : Promise.resolve("confirmed"))
        if (!(await keys.has())) return withoutKey()
        const at = this.unlockedAt
        if (!always && at !== undefined && now() - at < FRESH_UNLOCK_MS) return "confirmed"
        const read = await keys.read(prompt)
        switch (read.kind) {
            case "none":
                return withoutKey()
            case "cancelled":
            case "failed":
                return read.kind
            case "key":
                try {
                    await account.unlock(read.key)
                } catch {
                    return "refused"
                }
                this.unlockedAt = now()
                return "confirmed"
        }
    }

    async forget(): Promise<void> {
        this.unlockedAt = undefined
        await this.dependencies.keys.clear()
    }
}
