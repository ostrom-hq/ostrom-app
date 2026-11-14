// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.ReceiptCheck
import run.ostrom.app.domain.models.ReceiptPage
import run.ostrom.app.domain.models.ServedReceiptKey
import run.ostrom.app.domain.repositories.ReceiptKeyPinRepository
import run.ostrom.app.domain.repositories.ReceiptRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.receipts.CheckReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.GetReceiptUseCase
import run.ostrom.app.domain.usecases.receipts.TrustedReceiptKeysUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.repositories.readReceipt
import run.ostrom.app.presentation.navigation.ReceiptRoute
import run.ostrom.app.presentation.viewmodels.ReceiptViewModel
import run.ostrom.core.receipts.ReceiptSignerImpl
import run.ostrom.core.receipts.ReceiptSigningKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val key = ReceiptSigningKey.generate()
    private val accounts = accounts().also { it.save(account()) }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, MemoryCredentialStore(), FixedClock()))

    private fun signed(id: String): JsonObject = ReceiptSignerImpl(key).sign(buildJsonObject {
        put("id", id)
        put("subject", buildJsonObject { put("kind", "gate"); put("id", "g1"); put("digest", "ab".repeat(32)) })
        put("initiator", "charles")
        put("rules", buildJsonArray {})
        put("decisions", buildJsonArray {})
        put("createdAt", "2026-11-13T10:00:00.000Z")
    })

    private inner class Fake : ReceiptRepository {
        var answer: () -> JsonObject = { signed("rcpt_1") }
        var keysFail = false
        val asked = mutableListOf<String>()
        override suspend fun page(accountId: String, before: String?) = ReceiptPage(emptyList(), null)
        override suspend fun get(accountId: String, id: String): HistoryEntry = readReceipt(answer()).also { asked += "get $id" }
        override suspend fun keys(accountId: String): List<ServedReceiptKey> {
            if (keysFail) error("offline")
            return listOf(ServedReceiptKey(key.publicKey.kid, "Ed25519", key.publicKey.toSpkiPem()))
        }
    }

    private class Pins : ReceiptKeyPinRepository {
        var stored: Map<String, String> = emptyMap()
        override suspend fun read(origin: String) = stored
        override suspend fun write(origin: String, pins: Map<String, String>) { stored = pins }
    }

    private val receipts = Fake()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = ReceiptViewModel(
        ReceiptRoute("a1", "rcpt_1"),
        GetReceiptUseCase(receipts, FakeFeatures(setOf(Features.RECEIPTS)), guard),
        TrustedReceiptKeysUseCase(receipts, Pins(), accounts, guard, UnconfinedTestDispatcher(scheduler)),
        CheckReceiptsUseCase(UnconfinedTestDispatcher(scheduler)),
    )

    @Test
    fun theReceiptIsVerifiedAndARefreshKeepsItsResult() = runTest(dispatcher) {
        val viewModel = viewModel(testScheduler)
        advanceUntilIdle()
        val check = viewModel.uiState.value.entry?.check
        assertTrue(check is ReceiptCheck.Verified, "verified: $check")
        assertEquals("rcpt_1", viewModel.receiptId)
        // The keys fail on a refresh of the same receipt: what was verified stays verified.
        receipts.keysFail = true
        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entry?.check is ReceiptCheck.Verified)
        assertEquals(listOf("get rcpt_1", "get rcpt_1"), receipts.asked)
    }

    @Test
    fun keysThatCannotBeReadLeaveTheReceiptUncheckedAndARefreshChecksItAgain() = runTest(dispatcher) {
        receipts.keysFail = true
        val viewModel = viewModel(testScheduler)
        advanceUntilIdle()
        assertEquals(ReceiptCheck.NoKey, viewModel.uiState.value.entry?.check)
        receipts.keysFail = false
        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entry?.check is ReceiptCheck.Verified)
    }

    @Test
    fun aGoneReceiptThenAFailedRefreshOffersRetryAgain() = runTest(dispatcher) {
        receipts.answer = { throw AppException.NotFound() }
        val viewModel = viewModel(testScheduler)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.gone)
        assertNull(viewModel.uiState.value.entry)
        receipts.answer = { throw AppException.Unreachable() }
        viewModel.load()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.gone, "a failure that is not a 404 can be retried")
        assertNotNull(viewModel.uiState.value.error)
        // Answered under another id: gone, never shown as the one asked.
        receipts.answer = { signed("rcpt_2") }
        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.gone)
        assertNull(viewModel.uiState.value.entry)
    }
}
