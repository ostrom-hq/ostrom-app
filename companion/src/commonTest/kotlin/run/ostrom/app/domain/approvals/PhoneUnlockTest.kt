// SPDX-License-Identifier: AGPL-3.0-only
// The cases of the React Native app's PhoneUnlockImpl.test.ts, in Kotlin.
package run.ostrom.app.domain.approvals

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeApprovalRepository
import run.ostrom.app.FakeBiometricGate
import run.ostrom.app.FixedClock
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.UnlockOutcome
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.domain.services.NoBiometricGate
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhoneUnlockTest {
    private val credentials = MemoryCredentialStore()
    private val approvals = FakeApprovalRepository()
    private val gate = FakeBiometricGate()
    private val clock = FixedClock()
    private val unlock = PhoneUnlock(credentials, approvals, gate, clock)

    private suspend fun keep(key: String, account: String = "a1") = credentials.write(unlockKeyEntry(account), key)

    @Test
    fun aYesUnlocksForItsOwnRequestEveryTimeEvenRightAfterAnUnlock() = runTest {
        keep("key-1")
        assertEquals(UnlockOutcome.CONFIRMED, unlock.unlock("a1", "Decline", always = false))
        assertEquals("key-1" to null, approvals.unlocks.last())
        assertEquals(UnlockOutcome.CONFIRMED, unlock.unlock("a1", "Approve", always = false, requestId = "appr_0001"))
        assertEquals(2, gate.prompts.size, "A yes asks the biometrics again")
        assertEquals("key-1" to "appr_0001", approvals.unlocks.last())
    }

    @Test
    fun aLowRiskNoReusesAFreshUnlockUntilItIsTooOld() = runTest {
        keep("key-1")
        unlock.unlock("a1", "Decline", always = false)
        clock.now += PhoneUnlock.FRESH_UNLOCK_MILLIS - 1
        assertEquals(UnlockOutcome.CONFIRMED, unlock.unlock("a1", "Decline", always = false))
        assertEquals(1, gate.prompts.size, "A fresh unlock is reused")
        // An answer that needs the biometrics asks them again, however recent the unlock.
        unlock.unlock("a1", "Decline", always = true)
        assertEquals(2, gate.prompts.size)
        clock.now += PhoneUnlock.FRESH_UNLOCK_MILLIS
        unlock.unlock("a1", "Decline", always = false)
        assertEquals(3, gate.prompts.size, "An unlock older than four minutes is not reused")
        assertEquals(3, approvals.unlocks.size)
    }

    @Test
    fun goingToTheBackgroundEndsTheFreshUnlock() = runTest {
        keep("key-1")
        unlock.unlock("a1", "Decline", always = false)
        clock.now += 1_000
        unlock.onBackground()
        unlock.unlock("a1", "Decline", always = false)
        assertEquals(2, gate.prompts.size)
    }

    @Test
    fun aFreshUnlockIsPerAccountAndPerKey() = runTest {
        keep("key-1")
        keep("key-b", account = "b1")
        unlock.unlock("a1", "Decline", always = false)
        unlock.unlock("b1", "Decline", always = false)
        assertEquals(2, gate.prompts.size, "Another account unlocks on its own")
        // Signed in again: a new key, so the last unlock is not fresh for it.
        keep("key-2")
        unlock.unlock("a1", "Decline", always = false)
        assertEquals("key-2" to null, approvals.unlocks.last())
    }

    @Test
    fun withoutAKeyTheBiometricsAreAskedOnlyWhenTheAnswerNeedsThem() = runTest {
        gate.outcome = BiometricOutcome.CANCELLED
        assertEquals(UnlockOutcome.CANCELLED, unlock.unlock("a1", "Approve", always = true))
        assertEquals(listOf("Approve"), gate.prompts)
        assertEquals(UnlockOutcome.CONFIRMED, unlock.unlock("a1", "Approve", always = false, requestId = "appr_0001"))
        assertEquals(1, gate.prompts.size)
        assertTrue(approvals.unlocks.isEmpty(), "Nothing is sent to the server without a key")
    }

    @Test
    fun aComputerWithoutBiometricsLetsTheServerDecide() = runTest {
        val desktop = PhoneUnlock(credentials, approvals, NoBiometricGate, clock)
        assertEquals(UnlockOutcome.CONFIRMED, desktop.unlock("a1", "Approve", always = true, requestId = "appr_0001"))
        // A key with nothing to give it back is never sent without a check.
        keep("key-1")
        assertEquals(UnlockOutcome.UNAVAILABLE, desktop.unlock("a1", "Approve", always = true))
        assertTrue(approvals.unlocks.isEmpty())
        assertEquals(BiometricOutcome.UNAVAILABLE, NoBiometricGate.confirm("x"))
    }

    @Test
    fun theBiometricsOutcomeIsKeptAndNothingIsSentWithoutAConfirmation() = runTest {
        keep("key-1")
        for ((outcome, expected) in listOf(
            BiometricOutcome.CANCELLED to UnlockOutcome.CANCELLED,
            BiometricOutcome.FAILED to UnlockOutcome.FAILED,
            BiometricOutcome.UNAVAILABLE to UnlockOutcome.UNAVAILABLE,
        )) {
            gate.outcome = outcome
            assertEquals(expected, unlock.unlock("a1", "Approve", always = true))
        }
        assertTrue(approvals.unlocks.isEmpty())
    }

    @Test
    fun theServersRefusalOfTheKeyIsRefusedAndAskedAgainNextTime() = runTest {
        keep("key-1")
        for (status in listOf(400, 403)) {
            approvals.unlockError = AppException.Server(status, "unlock_refused")
            assertEquals(UnlockOutcome.REFUSED, unlock.unlock("a1", "Decline", always = false))
        }
        assertEquals(2, approvals.unlocks.size)
    }

    @Test
    fun otherFailuresGoThroughTheKeyIsNotRefused() = runTest {
        keep("key-1")
        for (error in listOf(AppException.Unreachable(), AppException.Server(502, null), AppException.Server(403, null), AppException.Server(409, "conflict"), AppException.Unauthorized())) {
            approvals.unlockError = error
            assertSame(error, assertFailsWith<Exception> { unlock.unlock("a1", "Decline", always = false) })
        }
        assertEquals("key-1", credentials.read(unlockKeyEntry("a1")))
    }

    @Test
    fun anUnlockThatAnswersAfterASignOutProvesNothing() = runTest {
        keep("key-1")
        approvals.duringUnlock = { credentials.delete(unlockKeyEntry("a1")); keep("key-2") }
        assertEquals(UnlockOutcome.CANCELLED, unlock.unlock("a1", "Decline", always = false))
        approvals.duringUnlock = {}
        assertEquals(UnlockOutcome.CONFIRMED, unlock.unlock("a1", "Decline", always = false))
        assertEquals("key-2" to null, approvals.unlocks.last())
    }
}
