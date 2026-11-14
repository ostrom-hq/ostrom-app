// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/repositories/PinnedReceiptsRepository.test.ts, src/domain/receipts/keyRotation.test.ts
// and src/ui/screens/history/receiptCheck.test.ts.
@file:OptIn(ExperimentalEncodingApi::class)

package run.ostrom.app.domain

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.ORIGIN
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.KeyHandover
import run.ostrom.app.domain.models.ReceiptCheck
import run.ostrom.app.domain.models.ReceiptCheckNode
import run.ostrom.app.domain.models.ReceiptPage
import run.ostrom.app.domain.models.ReceiptRejection
import run.ostrom.app.domain.models.ReceiptSummary
import run.ostrom.app.domain.models.RunOrigin
import run.ostrom.app.domain.models.ServedReceiptKey
import run.ostrom.app.domain.repositories.ReceiptKeyPinRepository
import run.ostrom.app.domain.repositories.ReceiptRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.receipts.CheckReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.LoadReceiptsUseCase
import run.ostrom.app.domain.usecases.receipts.ReceiptKeyPinning
import run.ostrom.app.domain.usecases.receipts.TrustedReceiptKeysUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.core.receipts.ReceiptPublicKey
import run.ostrom.core.receipts.ReceiptSignerImpl
import run.ostrom.core.receipts.ReceiptSigningKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptUseCasesTest {
    private val a = ReceiptSigningKey.generate()
    private val b = ReceiptSigningKey.generate()
    private val c = ReceiptSigningKey.generate()

    private fun ReceiptPublicKey.hex() = toRaw().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun served(key: ReceiptSigningKey, rotation: KeyHandover? = null) = ServedReceiptKey(key.publicKey.kid, "Ed25519", key.publicKey.toSpkiPem(), rotation)

    /** [old] hands over to [new], as the server does at a rotation. */
    private fun handover(old: ReceiptSigningKey, new: ReceiptSigningKey) =
        KeyHandover(old.publicKey.kid, Base64.encode(old.sign(ReceiptKeyPinning.rotationMessage(new.publicKey))))

    private fun pins(vararg keys: ReceiptSigningKey) = keys.associate { it.publicKey.kid to it.publicKey.hex() }

    private fun body(id: String = "rcpt_1") = buildJsonObject {
        put("id", id)
        put("subject", buildJsonObject { put("kind", "gate"); put("id", "g1"); put("digest", "ab".repeat(32)) })
        put("initiator", "charles")
        put("rules", buildJsonArray {})
        put("decisions", buildJsonArray {})
        put("createdAt", "2026-11-13T10:00:00.000Z")
        // A field this version does not know: signed, and kept by the check.
        put("futureField", "kept")
    }

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts(), MemoryCredentialStore(), FixedClock()))

    @Test
    fun theRotationMessageIsTheContextLineAndTheNewKeysRawHex() {
        val message = ReceiptKeyPinning.rotationMessage(b.publicKey).decodeToString()
        assertEquals("ostrom-receipt-key-rotation/v1\n" + b.publicKey.hex(), message)
        assertEquals(64, message.substringAfter('\n').length)
    }

    @Test
    fun aFirstViewTrustsAndPinsTheServedKeys() {
        val trust = ReceiptKeyPinning.trust(listOf(served(a)), emptyMap())
        assertEquals(listOf(a.publicKey.kid), trust.keys.map { it.kid })
        assertEquals(pins(a), trust.pins)
        assertTrue(trust.changed)
        // Seen again: nothing new to keep.
        assertFalse(ReceiptKeyPinning.trust(listOf(served(a)), trust.pins).changed)
    }

    @Test
    fun aNewKeyWithoutARotationIsNotTrusted() {
        val trust = ReceiptKeyPinning.trust(listOf(served(a), served(b)), pins(a))
        assertEquals(listOf(a.publicKey.kid), trust.keys.map { it.kid })
        assertFalse(trust.changed)
        // The old key gone: nothing is trusted, the receipts read as signed by an unknown key.
        assertTrue(ReceiptKeyPinning.trust(listOf(served(b)), pins(a)).keys.isEmpty())
    }

    @Test
    fun aRotationSignedByATrustedKeyIsTrustedTransitively() {
        // C from B from A, served newest first: the loop finds B before C.
        val trust = ReceiptKeyPinning.trust(listOf(served(c, handover(b, c)), served(b, handover(a, b))), pins(a))
        assertEquals(setOf(b.publicKey.kid, c.publicKey.kid), trust.keys.map { it.kid }.toSet())
        assertEquals(pins(a, b, c), trust.pins)
        assertTrue(trust.changed)
    }

    @Test
    fun aRotationSignedByAnUntrustedKeyOrUnreadableIsRefused() {
        assertTrue(ReceiptKeyPinning.trust(listOf(served(c, handover(b, c))), pins(a)).keys.isEmpty(), "B is not trusted")
        // Claims A handed over, but B signed it.
        val forged = KeyHandover(a.publicKey.kid, handover(b, c).signature)
        assertTrue(ReceiptKeyPinning.trust(listOf(served(c, forged)), pins(a)).keys.isEmpty())
        assertTrue(ReceiptKeyPinning.trust(listOf(served(c, KeyHandover(a.publicKey.kid, "not base64!"))), pins(a)).keys.isEmpty())
        assertTrue(ReceiptKeyPinning.trust(listOf(served(c, KeyHandover(a.publicKey.kid, Base64.encode(ByteArray(10))))), pins(a)).keys.isEmpty())
        assertFalse(ReceiptKeyPinning.verifyRotation(c.publicKey, handover(a, b).signature, a.publicKey), "signed for another key")
    }

    @Test
    fun keysAreComparedByTheirRawBytesNotOnlyTheirId() {
        // The pin of A's id holds B's key: A, served under its own id, is not the key kept.
        val swapped = mapOf(a.publicKey.kid to b.publicKey.hex())
        assertTrue(ReceiptKeyPinning.trust(listOf(served(a)), swapped).keys.isEmpty())
    }

    @Test
    fun unreadablePinsFailClosed() {
        val unread = ReceiptKeyPinning.trust(listOf(served(a)), null)
        assertTrue(unread.keys.isEmpty())
        assertFalse(unread.changed)
        assertTrue(ReceiptKeyPinning.trust(listOf(served(a)), mapOf(a.publicKey.kid to "not hex")).keys.isEmpty())
    }

    @Test
    fun onlyAnEd25519KeyUnderItsOwnIdIsUsable() {
        assertEquals(a.publicKey.kid, ReceiptKeyPinning.usable(served(a))?.kid)
        assertNull(ReceiptKeyPinning.usable(served(a).copy(alg = "RS256")))
        assertNull(ReceiptKeyPinning.usable(served(a).copy(kid = b.publicKey.kid)), "a server naming one key and serving another")
        assertNull(ReceiptKeyPinning.usable(served(a).copy(publicKeyPem = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----")))
    }

    @Test
    fun eachReceiptIsCheckedOnTheDevice() = runTest {
        val signed = ReceiptSignerImpl(a).sign(body())
        val tampered = JsonObject(signed + ("initiator" to JsonPrimitive("mallory")))
        val byB = ReceiptSignerImpl(b).sign(body("rcpt_2"))
        val unsigned = body("rcpt_3")
        val bad = JsonObject(signed + ("signature" to JsonPrimitive("rsa;kid=x;sha256=y;sig=z")))
        val check = CheckReceiptsUseCase()
        val results = check(listOf(signed, tampered, byB, unsigned, bad).map { HistoryEntry(it, null) }, listOf(a.publicKey)).map { it.check }
        assertEquals(
            listOf(
                ReceiptCheck.Verified(a.publicKey.kid),
                ReceiptCheck.Rejected(ReceiptRejection.DIGEST_MISMATCH),
                ReceiptCheck.Rejected(ReceiptRejection.UNKNOWN_KEY),
                ReceiptCheck.Rejected(ReceiptRejection.MISSING_SIGNATURE),
                ReceiptCheck.Rejected(ReceiptRejection.UNKNOWN_ALGORITHM),
            ),
            results,
        )
        assertEquals(listOf(ReceiptCheck.NoKey), check(listOf(HistoryEntry(signed, null)), emptyList()).map { it.check })
    }

    @Test
    fun aCheckRunCountsAsProvenOnlyOnAVerifiedReceipt() {
        val nodes = listOf(
            ReceiptCheckNode("Build", "passed", RunOrigin.CI),
            ReceiptCheckNode("Tests", "passed", RunOrigin.ISOLATED_RUNNER),
            ReceiptCheckNode("Lint", "passed", null),
            ReceiptCheckNode("Fmt", "passed", RunOrigin.UNKNOWN),
        )
        val entry = HistoryEntry(body(), ReceiptSummary("rcpt_1", null, false, "charles", "workflow", "w1", null, nodes))
        assertEquals(listOf(RunOrigin.UNVERIFIED, RunOrigin.UNVERIFIED, RunOrigin.WORKSTATION_DECLARED, RunOrigin.UNKNOWN), entry.checkRuns.map { it.origin })
        val verified = entry.copy(check = ReceiptCheck.Verified("k"))
        assertEquals(listOf(RunOrigin.CI, RunOrigin.ISOLATED_RUNNER, RunOrigin.WORKSTATION_DECLARED, RunOrigin.UNKNOWN), verified.checkRuns.map { it.origin })
        assertEquals(listOf(true, true, false, false), verified.checkRuns.map { it.origin.proven })
        assertEquals("Build", verified.checkRuns.first().label)
        assertTrue(HistoryEntry(body(), null).checkRuns.isEmpty())
    }

    private class FakeReceipts(var keys: List<ServedReceiptKey> = emptyList()) : ReceiptRepository {
        val asked = mutableListOf<String>()
        override suspend fun page(accountId: String, before: String?): ReceiptPage {
            asked += "page ${before.orEmpty()}".trim()
            return ReceiptPage(emptyList(), "c2")
        }

        override suspend fun keys(accountId: String): List<ServedReceiptKey> = keys
    }

    private class FakePins(var stored: Map<String, String>? = emptyMap()) : ReceiptKeyPinRepository {
        var readFails = false
        var writeFails = false
        val written = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun read(origin: String): Map<String, String>? = if (readFails) error("locked") else stored

        override suspend fun write(origin: String, pins: Map<String, String>) {
            if (writeFails) error("refused")
            written += origin to pins
            stored = pins
        }
    }

    @Test
    fun theReceiptsAreHiddenFromAServerWithoutThem() = runTest {
        val receipts = FakeReceipts(); val features = FakeFeatures()
        val load = LoadReceiptsUseCase(receipts, features, guard())
        assertFailsWith<AppException.Unsupported> { load("a1") }
        assertTrue(receipts.asked.isEmpty())
        features.features = setOf(Features.RECEIPTS)
        assertEquals("c2", load("a1").next)
        load("a1", "c2")
        assertEquals(listOf("page", "page c2"), receipts.asked)
    }

    @Test
    fun theTrustedKeysArePinnedByServerOrigin() = runTest {
        val receipts = FakeReceipts(listOf(served(a)))
        val pins = FakePins()
        val list = accounts().also { it.save(account()) }
        val trusted = TrustedReceiptKeysUseCase(receipts, pins, list, guard())
        assertEquals(listOf(a.publicKey.kid), trusted("a1").map { it.kid })
        assertEquals(listOf(ORIGIN to pins(a)), pins.written)
        // A key served later, handed over by nobody: not trusted, nothing written.
        receipts.keys = listOf(served(b))
        assertTrue(trusted("a1").isEmpty())
        assertEquals(1, pins.written.size)
        // Handed over by A: trusted and kept.
        receipts.keys = listOf(served(b, handover(a, b)))
        assertEquals(listOf(b.publicKey.kid), trusted("a1").map { it.kid })
        assertEquals(pins(a, b), pins.stored)
        assertFailsWith<AppException.Unauthorized> { trusted("unknown") }
    }

    @Test
    fun pinsThatCannotBeReadTrustNothingAndPinsThatCannotBeWrittenStillServe() = runTest {
        val receipts = FakeReceipts(listOf(served(a)))
        val list = accounts().also { it.save(account()) }
        val unreadable = FakePins(stored = null)
        assertTrue(TrustedReceiptKeysUseCase(receipts, unreadable, list, guard())("a1").isEmpty())
        assertTrue(unreadable.written.isEmpty(), "unreadable pins are left as they are")
        val locked = FakePins().apply { readFails = true }
        assertTrue(TrustedReceiptKeysUseCase(receipts, locked, list, guard())("a1").isEmpty())
        val refusing = FakePins().apply { writeFails = true }
        assertEquals(listOf(a.publicKey.kid), TrustedReceiptKeysUseCase(receipts, refusing, list, guard())("a1").map { it.kid })
    }
}
