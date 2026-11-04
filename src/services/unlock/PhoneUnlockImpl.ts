// SPDX-License-Identifier: AGPL-3.0-only
import {ApiError} from "@/api/http/ApiError"
import type {AccountRepository} from "@/repositories/AccountRepository"
import type {BiometricGate} from "@/services/biometrics/BiometricGate"
import type {PhoneUnlock, UnlockOptions, UnlockOutcome} from "./PhoneUnlock"
import type {UnlockKeyStore} from "./UnlockKeyStore"

/** The server takes a no 5 minutes after an unlock; the app unlocks again one minute before. A yes always unlocks. */
export const FRESH_UNLOCK_MS = 4 * 60 * 1000

export interface PhoneUnlockDependencies {
    readonly keys: UnlockKeyStore
    readonly biometrics: BiometricGate
    /** The clock, in milliseconds; the phone's by default. */
    readonly now?: () => number
    /**
     * Subscribes to the app going to the background (`onBackground` in the app); never by default. A low-risk yes
     * without biometrics is for the person still in the app: leaving it ends that (AUDIT-SECU-APP-2 N4).
     */
    readonly leaving?: (listener: () => void) => void
}

/** The server's own no to the key, in the contract's error body: a proxy's bare 403 is no word on the key. */
function isRefusal(error: unknown): boolean {
    return error instanceof ApiError && error.problem.kind === "refused" && (error.problem.status === 400 || error.problem.status === 403)
}

function phoneNow(): number {
    return Date.now()
}

export class PhoneUnlockImpl implements PhoneUnlock {
    private unlockedAt: number | undefined
    /** Counts the sessions (a key kept, a key forgotten): an unlock started in one proves nothing for the next. */
    private session = 0

    constructor(private readonly dependencies: PhoneUnlockDependencies) {
        dependencies.leaving?.(() => {
            this.unlockedAt = undefined
        })
    }

    keep(unlockKey: string): Promise<void> {
        this.session += 1
        this.unlockedAt = undefined
        return this.dependencies.keys.save(unlockKey)
    }

    async unlock(prompt: string, account: AccountRepository, {always, requestId}: UnlockOptions): Promise<UnlockOutcome> {
        const {keys, biometrics, now = phoneNow} = this.dependencies
        const asked = this.session
        // No key (a server before the unlock, or a session kept before it): the app's own check, as before.
        const withoutKey = (): Promise<UnlockOutcome> => (always ? biometrics.confirm(prompt) : Promise.resolve("confirmed"))
        if (!(await keys.has())) return withoutKey()
        const at = this.unlockedAt
        // A yes names its request: an unlock made for another, or for none, is no proof of it for the server.
        if (!always && requestId === undefined && at !== undefined && now() - at < FRESH_UNLOCK_MS) return "confirmed"
        const read = await keys.read(prompt)
        switch (read.kind) {
            case "none":
                // Kept, but gone from the keychain (passcode removed, keychain reset): only a new sign-in brings one.
                if (asked === this.session) await keys.clear().catch(() => undefined)
                return "refused"
            case "cancelled":
            case "failed":
                return read.kind
            case "key": {
                try {
                    await (requestId === undefined ? account.unlock(read.key) : account.unlock(read.key, requestId))
                } catch (error) {
                    // Only the server's no refuses the key. Offline, a 5xx or an expired session is the answer's own
                    // problem, said as such by the screen.
                    if (isRefusal(error)) return "refused"
                    throw error
                }
                // Started in a session that has ended: it proves nothing, and no answer leaves on it.
                if (asked !== this.session) return "cancelled"
                this.unlockedAt = now()
                return "confirmed"
            }
        }
    }

    async forget(): Promise<void> {
        this.session += 1
        this.unlockedAt = undefined
        await this.dependencies.keys.clear()
    }
}
