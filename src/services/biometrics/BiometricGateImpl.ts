// SPDX-License-Identifier: AGPL-3.0-only
import * as LocalAuthentication from "expo-local-authentication"
import type {BiometricGate, BiometricOutcome} from "./BiometricGate"

const CANCELLED: ReadonlySet<LocalAuthentication.LocalAuthenticationError> = new Set([
    "user_cancel",
    "system_cancel",
    "app_cancel",
])
const UNAVAILABLE: ReadonlySet<LocalAuthentication.LocalAuthenticationError> = new Set([
    "not_enrolled",
    "passcode_not_set",
    "not_available",
])

export class BiometricGateImpl implements BiometricGate {
    async confirm(prompt: string): Promise<BiometricOutcome> {
        const level = await LocalAuthentication.getEnrolledLevelAsync()
        if (level === LocalAuthentication.SecurityLevel.NONE) return "unavailable"
        // The device passcode stays allowed as fallback: Face ID fails with a mask, gloves, a cracked sensor.
        let result: LocalAuthentication.LocalAuthenticationResult
        try {
            result = await LocalAuthentication.authenticateAsync({promptMessage: prompt, disableDeviceFallback: false})
        } catch {
            return "failed"
        }
        if (result.success) return "confirmed"
        if (CANCELLED.has(result.error)) return "cancelled"
        if (UNAVAILABLE.has(result.error)) return "unavailable"
        return "failed"
    }
}
