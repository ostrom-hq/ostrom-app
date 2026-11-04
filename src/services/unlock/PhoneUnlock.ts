// SPDX-License-Identifier: AGPL-3.0-only
import type {AccountRepository} from "@/repositories/AccountRepository"
import type {BiometricOutcome} from "@/services/biometrics/BiometricGate"

/** A biometric outcome, or `refused`: the server did not take this phone's key (another token, a revoked one). */
export type UnlockOutcome = BiometricOutcome | "refused"

export interface UnlockOptions {
    /** Biometrics for this answer whatever the last unlock (anything but a low-risk action). */
    readonly always: boolean
    /**
     * The request a yes is for: the unlock is made for it, with biometrics, whatever the last unlock. The server takes
     * a yes only with an unlock bound to its request, less than a minute old, used once (Q-APP-STEPUP-RISK).
     */
    readonly requestId?: string
}

/**
 * Before an answer, the phone proves to the server that its person is there (server#75, S2-54): the biometrics give
 * back the unlock key, and `POST /auth/mobile/unlock` records it; the server takes a no for 5 minutes after, and a yes
 * only with an unlock made for its request.
 */
export interface PhoneUnlock {
    /** Keeps the key of the token exchange behind the biometrics. */
    keep(unlockKey: string): Promise<void>
    unlock(prompt: string, account: AccountRepository, options: UnlockOptions): Promise<UnlockOutcome>
    /** At sign-out: the key goes with the token. */
    forget(): Promise<void>
}
