// SPDX-License-Identifier: AGPL-3.0-only
/** The outcome of asking the person to prove it is them before a decision. */
export type BiometricOutcome =
    | "confirmed"
    /** The person dismissed the prompt: nothing is sent. */
    | "cancelled"
    /** The phone has no biometrics and no passcode: a decision cannot be signed from it. */
    | "unavailable"
    /** Too many attempts, or the system failed: nothing is sent. */
    | "failed"

/** Face ID, Touch ID, fingerprint, or the device passcode as fallback, before every yes or no. */
export interface BiometricGate {
    confirm(prompt: string): Promise<BiometricOutcome>
}
