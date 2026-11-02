// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError} from "@/api/http/ApiError"
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

/** The server's no to the key, with or without the contract's error body (a proxy answers without it). */
function isRefusal(error: unknown): boolean {
    if (!(error instanceof ApiError)) return false
    const {problem} = error
    return (problem.kind === "refused" || problem.kind === "unexpected-status") && (problem.status === 400 || problem.status === 403)
}

function phoneNow(): number {
    return Date.now()
}

export class PhoneUnlockImpl implements PhoneUnlock {
    private unlockedAt: number | undefined
    /** Counts the sign-outs: an unlock that answers after one proves nothing for the next session. */
    private forgets = 0

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
                // Kept, but gone from the keychain (passcode removed, keychain reset): only a new sign-in brings one.
                await keys.clear().catch(() => undefined)
                return "refused"
            case "cancelled":
            case "failed":
                return read.kind
            case "key": {
                const asked = this.forgets
                try {
                    await account.unlock(read.key)
                } catch (error) {
                    // Only the server's no refuses the key. Offline, a 5xx or an expired session is the answer's own
                    // problem, said as such by the screen.
                    if (isRefusal(error)) return "refused"
                    throw error
                }
                if (asked === this.forgets) this.unlockedAt = now()
                return "confirmed"
            }
        }
    }

    async forget(): Promise<void> {
        this.forgets += 1
        this.unlockedAt = undefined
        await this.dependencies.keys.clear()
    }
}
