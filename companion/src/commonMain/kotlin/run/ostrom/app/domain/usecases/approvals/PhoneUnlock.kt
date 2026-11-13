// SPDX-License-Identifier: Apache-2.0
// The rules of the React Native app's PhoneUnlockImpl.ts, in Kotlin.
package run.ostrom.app.domain.usecases.approvals

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.UnlockOutcome
import run.ostrom.app.domain.repositories.ApprovalRepository
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.unlockKeyEntry

/**
 * Before an answer, the device proves to the server that its person is there: the biometrics give the unlock key of
 * the sign-in back (kept in the [CredentialStore] under [unlockKeyEntry]), and `POST /auth/mobile/unlock` records it.
 * The server takes a no for 5 minutes after, and a yes only with an unlock made for its request.
 *
 * - A yes always unlocks, bound to its request id: one biometric, one yes.
 * - A no reuses an unlock younger than [FRESH_UNLOCK_MILLIS], unless the action needs the biometrics (`always`).
 * - Going to the background ([onBackground]) ends every fresh unlock: it was for the person who left the app.
 * - No key at all (a computer, a server before the unlock): the local check only when `always`, where there is one.
 * - The server's refusal of the key is [UnlockOutcome.REFUSED]: only a new sign-in brings another key.
 */
class PhoneUnlock(
    private val credentials: CredentialStore,
    private val approvals: ApprovalRepository,
    private val biometrics: BiometricGate,
    private val clock: Clock,
) {
    /**
     * When an account last unlocked, and the key it unlocked with: a new sign-in (another key) is never fresh. Kept in
     * memory only, for the few minutes it counts, and never written anywhere.
     */
    private class Fresh(val at: Long, val key: String)

    private val fresh = MutableStateFlow<Map<String, Fresh>>(emptyMap())

    /** The app went to the background: the next answer asks the biometrics again. */
    fun onBackground() {
        fresh.value = emptyMap()
    }

    /**
     * Proves the person is there before an answer of [accountId].
     *
     * @param prompt What the system's prompt says (the answer and the request's title).
     * @param always The answer needs the biometrics, however recent the last unlock.
     * @param requestId The request a yes is for: the unlock is made for it, whatever the last unlock.
     * @throws AppException When the unlock could not reach the server or it failed otherwise: nothing was sent.
     */
    suspend fun unlock(accountId: String, prompt: String, always: Boolean, requestId: String? = null): UnlockOutcome {
        val entry = unlockKeyEntry(accountId)
        val key = credentials.read(entry)
            ?: return if (always && biometrics.supported) biometrics.confirm(prompt).asUnlock() else UnlockOutcome.CONFIRMED
        val last = fresh.value[accountId]
        if (!always && requestId == null && last != null && last.key == key && clock.nowMillis() - last.at < FRESH_UNLOCK_MILLIS) {
            return UnlockOutcome.CONFIRMED
        }
        // A key without any biometrics to give it back cannot happen on a phone; never sent without a check.
        if (!biometrics.supported) return UnlockOutcome.UNAVAILABLE
        val confirmed = biometrics.confirm(prompt)
        if (confirmed != BiometricOutcome.CONFIRMED) return confirmed.asUnlock()
        try {
            approvals.unlock(accountId, key, requestId)
        } catch (error: AppException.Server) {
            // Only the server's own no to the key (its error body, 400 or 403) refuses it: offline, a 5xx or a proxy's
            // bare 403 is the answer's own problem.
            if (error.code != null && (error.statusCode == BAD_REQUEST || error.statusCode == FORBIDDEN)) {
                fresh.update { it - accountId }
                return UnlockOutcome.REFUSED
            }
            throw error
        }
        // Signed out, or in again, while the unlock was on its way: it proves nothing for the session now kept.
        if (credentials.read(entry) != key) return UnlockOutcome.CANCELLED
        fresh.update { it + (accountId to Fresh(clock.nowMillis(), key)) }
        return UnlockOutcome.CONFIRMED
    }

    private fun BiometricOutcome.asUnlock(): UnlockOutcome = when (this) {
        BiometricOutcome.CONFIRMED -> UnlockOutcome.CONFIRMED
        BiometricOutcome.CANCELLED -> UnlockOutcome.CANCELLED
        BiometricOutcome.UNAVAILABLE -> UnlockOutcome.UNAVAILABLE
        BiometricOutcome.FAILED -> UnlockOutcome.FAILED
    }

    companion object {
        /** The server takes a no 5 minutes after an unlock; the app unlocks again one minute before. */
        const val FRESH_UNLOCK_MILLIS = 4 * 60_000L

        private const val BAD_REQUEST = 400
        private const val FORBIDDEN = 403
    }
}
