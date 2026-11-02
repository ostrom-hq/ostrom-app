// SPDX-License-Identifier: AGPL-3.0-only
/** What reading the unlock key gave: the key, none kept, or the person or the phone said no to the biometrics. */
export type UnlockKeyRead =
    | {readonly kind: "key"; readonly key: string}
    | {readonly kind: "none"}
    | {readonly kind: "cancelled"}
    | {readonly kind: "failed"}

/**
 * The unlock key of the token exchange (server#75), kept where only the phone's biometrics give it back: reading it
 * is the Face ID, fingerprint or passcode prompt. Whether one is kept is known without the prompt.
 */
export interface UnlockKeyStore {
    save(key: string): Promise<void>
    has(): Promise<boolean>
    read(prompt: string): Promise<UnlockKeyRead>
    clear(): Promise<void>
}
