// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
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
import kotlin.test.assertTrue

/** A refresh of a receipt read again unchanged keeps a verified or a rejected result; a changed one is checked again. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModelCoverageTest {
    private val dispatcher = StandardTestDispatcher()
    private val key = ReceiptSigningKey.generate()
    private val accounts = accounts().also { it.save(account()) }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, MemoryCredentialStore(), FixedClock()))

    private fun signed(initiator: String = "charles"): JsonObject = ReceiptSignerImpl(key).sign(buildJsonObject {
        put("id", "rcpt_1")
        put("subject", buildJsonObject { put("kind", "gate"); put("id", "g1"); put("digest", "ab".repeat(32)) })
        put("initiator", initiator)
        put("rules", buildJsonArray {})
        put("decisions", buildJsonArray {})
        put("createdAt", "2026-11-13T10:00:00.000Z")
    })

    /** Signed, then edited: the signature no longer matches what it covers. */
    private fun tampered(): JsonObject = JsonObject(signed() + ("initiator" to JsonPrimitive("mallory")))

    private inner class Fake : ReceiptRepository {
        var answer: () -> JsonObject = { signed() }
        var keysFail = false
        override suspend fun page(accountId: String, before: String?) = ReceiptPage(emptyList(), null)
        override suspend fun get(accountId: String, id: String): HistoryEntry = readReceipt(answer())
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

    private fun viewModel(scheduler: TestCoroutineScheduler) = ReceiptViewModel(
        ReceiptRoute("a1", "rcpt_1"),
        GetReceiptUseCase(receipts, FakeFeatures(setOf(Features.RECEIPTS)), guard),
        TrustedReceiptKeysUseCase(receipts, Pins(), accounts, guard, UnconfinedTestDispatcher(scheduler)),
        CheckReceiptsUseCase(UnconfinedTestDispatcher(scheduler)),
    )

    @Test fun aRejectedReceiptStaysRejectedWhenReadAgainUnchangedAndIsCheckedAgainWhenItChanged() = runTest(dispatcher) {
        receipts.answer = { tampered() }
        val model = viewModel(testScheduler)
        advanceUntilIdle()
        assertTrue(model.uiState.value.entry?.check is ReceiptCheck.Rejected, "rejected: ${model.uiState.value.entry?.check}")
        // The keys fail on the refresh: re-checking it would read as "no key", so the result kept proves it was not.
        receipts.keysFail = true
        model.load()
        advanceUntilIdle()
        assertTrue(model.uiState.value.entry?.check is ReceiptCheck.Rejected)
        assertEquals(false, model.uiState.value.loading)
        // The server now answers a receipt that holds: another body, so another check.
        receipts.keysFail = false
        receipts.answer = { signed() }
        model.load()
        advanceUntilIdle()
        assertTrue(model.uiState.value.entry?.check is ReceiptCheck.Verified)
    }

    @Test fun aVerifiedReceiptThatChangedIsCheckedAgain() = runTest(dispatcher) {
        val model = viewModel(testScheduler)
        advanceUntilIdle()
        assertTrue(model.uiState.value.entry?.check is ReceiptCheck.Verified)
        // Another body: the earlier result does not carry over, even with the keys unreadable.
        receipts.keysFail = true
        receipts.answer = { signed(initiator = "ada") }
        model.load()
        advanceUntilIdle()
        assertEquals(ReceiptCheck.NoKey, model.uiState.value.entry?.check)
    }
}
