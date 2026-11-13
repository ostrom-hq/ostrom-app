// SPDX-License-Identifier: Apache-2.0
// The React Native app's BiometricGate, in Kotlin.
package run.ostrom.app.domain.services

/** The outcome of asking the person to prove it is them before an answer. */
enum class BiometricOutcome {
    CONFIRMED,

    /** The person dismissed the prompt: nothing is sent. */
    CANCELLED,

    /** The device has no biometrics and no passcode: an answer cannot be given from it. */
    UNAVAILABLE,

    /** Too many attempts, or the system failed: nothing is sent. */
    FAILED,
}

/**
 * Face ID, Touch ID, a fingerprint, or the device's passcode as fallback, before an answer: BiometricPrompt with the
 * device credential on Android, `LAContext` with `deviceOwnerAuthentication` on iOS. The desktop binds none
 * ([NoBiometricGate]).
 */
interface BiometricGate {
    /** False where the platform has no biometrics at all (the desktop). */
    val supported: Boolean get() = true

    suspend fun confirm(prompt: String): BiometricOutcome
}

/**
 * The desktop's: no biometrics. A computer signs in with the device flow and holds no unlock key, so its answers go
 * with its token alone and the server decides (`403 step_up_required` sends the yes to the console).
 */
object NoBiometricGate : BiometricGate {
    override val supported: Boolean get() = false

    override suspend fun confirm(prompt: String): BiometricOutcome = BiometricOutcome.UNAVAILABLE
}
