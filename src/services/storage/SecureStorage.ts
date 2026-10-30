// SPDX-License-Identifier: AGPL-3.0-only
/** Small secrets kept by the phone's keychain (iOS) or keystore (Android). */
export interface SecureStorage {
    get(key: string): Promise<string | undefined>
    set(key: string, value: string): Promise<void>
    remove(key: string): Promise<void>
}
