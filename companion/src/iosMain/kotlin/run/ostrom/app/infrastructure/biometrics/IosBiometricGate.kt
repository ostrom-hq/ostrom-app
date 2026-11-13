// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.infrastructure.biometrics

import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome

/**
 * LocalAuthentication's `deviceOwnerAuthentication`: Face ID or Touch ID, with the device passcode as fallback. The
 * unlock key stays in the Keychain item the sign-in wrote; this prompt is what the app asks before reading it. The
 * prompt's reason is shown under Face ID (the `NSFaceIDUsageDescription` of Info.plist says why the app uses it).
 */
@OptIn(ExperimentalForeignApi::class)
class IosBiometricGate : BiometricGate {
    override suspend fun confirm(prompt: String): BiometricOutcome = withContext(Dispatchers.Main) {
        val context = LAContext()
        val unavailable = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr)) null else outcomeOf(error.value)
        }
        if (unavailable != null) return@withContext unavailable
        suspendCancellableCoroutine { continuation ->
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = prompt) { success, error ->
                val outcome = if (success) BiometricOutcome.CONFIRMED else outcomeOf(error)
                if (continuation.isActive) continuation.resume(outcome)
            }
            continuation.invokeOnCancellation { context.invalidate() }
        }
    }

    private fun outcomeOf(error: NSError?): BiometricOutcome = when (error?.code) {
        LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel -> BiometricOutcome.CANCELLED
        LAErrorPasscodeNotSet, LAErrorBiometryNotEnrolled, LAErrorBiometryNotAvailable -> BiometricOutcome.UNAVAILABLE
        else -> BiometricOutcome.FAILED
    }
}
