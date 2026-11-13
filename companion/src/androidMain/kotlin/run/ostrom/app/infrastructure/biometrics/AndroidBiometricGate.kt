// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure.biometrics

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome

/**
 * androidx.biometric's `BiometricPrompt`, with the device's PIN, pattern or password as fallback (a fingerprint fails
 * with wet fingers, a face with a mask). The unlock key itself stays in the Keystore-backed `CredentialStore`, where the
 * sign-in put it: this prompt is what the app asks before reading it.
 */
class AndroidBiometricGate : BiometricGate {
    override suspend fun confirm(prompt: String): BiometricOutcome = withContext(Dispatchers.Main) {
        val activity = ForegroundActivity.current ?: return@withContext BiometricOutcome.FAILED
        // Strong biometrics or the device credential from Android 11; before, the pair Android accepts.
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        when (BiometricManager.from(activity).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED, BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            -> return@withContext BiometricOutcome.UNAVAILABLE
            else -> return@withContext BiometricOutcome.FAILED
        }
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(BiometricOutcome.CONFIRMED)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(outcomeOf(errorCode))
                }
                // onAuthenticationFailed: one attempt not recognized; the prompt stays and lets the person try again.
            }
            val main = ContextCompat.getMainExecutor(activity)
            val biometricPrompt = BiometricPrompt(activity, main, callback)
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(prompt.ifBlank { "Ostrom" }).setAllowedAuthenticators(authenticators).build()
            biometricPrompt.authenticate(info)
            // Cancelled from any thread (the screen left): the prompt is closed on the main thread, where it lives.
            continuation.invokeOnCancellation { main.execute { biometricPrompt.cancelAuthentication() } }
        }
    }

    private fun outcomeOf(errorCode: Int): BiometricOutcome = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> BiometricOutcome.CANCELLED
        BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> BiometricOutcome.UNAVAILABLE
        else -> BiometricOutcome.FAILED
    }
}

/** The activity the prompt shows over, kept by the activity itself (weakly: a destroyed one is never held). */
object ForegroundActivity {
    @Volatile
    private var activity: WeakReference<FragmentActivity>? = null

    val current: FragmentActivity? get() = activity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun attach(activity: FragmentActivity) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (this.activity?.get() === activity) this.activity = null
    }
}
