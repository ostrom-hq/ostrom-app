import * as LocalAuthentication from "expo-local-authentication"
import type {BiometricOutcome} from "./BiometricGate"
import {BiometricGateImpl} from "./BiometricGateImpl"

const FAILURES: readonly (readonly [LocalAuthentication.LocalAuthenticationError, BiometricOutcome])[] = [
    ["user_cancel", "cancelled"],
    ["system_cancel", "cancelled"],
    ["not_enrolled", "unavailable"],
    ["passcode_not_set", "unavailable"],
    ["lockout", "failed"],
    ["authentication_failed", "failed"],
]

jest.mock("expo-local-authentication", () => ({
    SecurityLevel: {NONE: 0, SECRET: 1, BIOMETRIC_WEAK: 2, BIOMETRIC_STRONG: 3},
    getEnrolledLevelAsync: jest.fn(),
    authenticateAsync: jest.fn(),
}))

const level = jest.mocked(LocalAuthentication.getEnrolledLevelAsync)
const authenticate = jest.mocked(LocalAuthentication.authenticateAsync)

describe("BiometricGateImpl", () => {
    const gate = new BiometricGateImpl()

    it("refuses to sign from a phone without biometrics or passcode", async () => {
        level.mockResolvedValue(LocalAuthentication.SecurityLevel.NONE)
        await expect(gate.confirm("Approve")).resolves.toBe("unavailable")
        expect(authenticate).not.toHaveBeenCalled()
    })

    it("confirms, with the passcode allowed as fallback", async () => {
        level.mockResolvedValue(LocalAuthentication.SecurityLevel.BIOMETRIC_STRONG)
        authenticate.mockResolvedValue({success: true})
        await expect(gate.confirm("Approve")).resolves.toBe("confirmed")
        expect(authenticate).toHaveBeenCalledWith({promptMessage: "Approve", disableDeviceFallback: false})
    })

    it("fails closed when the system prompt itself fails", async () => {
        level.mockResolvedValue(LocalAuthentication.SecurityLevel.BIOMETRIC_STRONG)
        authenticate.mockRejectedValue(new Error("no activity"))
        await expect(gate.confirm("Approve")).resolves.toBe("failed")
    })

    it.each(FAILURES)("maps %s to %s", async (error, outcome) => {
        level.mockResolvedValue(LocalAuthentication.SecurityLevel.SECRET)
        authenticate.mockResolvedValue({success: false, error})
        await expect(gate.confirm("Reject")).resolves.toBe(outcome)
    })
})
