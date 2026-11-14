// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/repositories/PinnedReceiptsRepository.ts and src/domain/receipts/keyRotation.ts.
@file:OptIn(ExperimentalEncodingApi::class)

package run.ostrom.app.domain.usecases.receipts

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import run.ostrom.app.domain.models.ServedReceiptKey
import run.ostrom.core.receipts.ReceiptPublicKey

/**
 * Which served receipt keys this device trusts. Trust on first view of a server: its keys are kept (pinned) then. A key
 * that appears later is trusted only through a rotation signed by a trusted key, or by a key rotated from one (C from B
 * from A); otherwise the receipts it signs read as signed by an unknown key, never as verified. Pins that cannot be read
 * fail closed: no key is trusted. Keys are compared by their raw bytes, not only by their 16-hex id. A kept key is never
 * revoked: a key that leaked can still sign a rotation.
 */
object ReceiptKeyPinning {
    /** What the replaced key signs, before the new key's raw bytes in lowercase hex. */
    const val ROTATION_CONTEXT = "ostrom-receipt-key-rotation/v1\n"

    private const val SIGNATURE_BYTES = 64
    private val RAW_KEY_HEX = Regex("^[0-9a-f]{64}$")
    private val base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /** The keys to verify with, the pins to keep and whether they changed (a first view, or a new key handed over). */
    data class Trust(val keys: List<ReceiptPublicKey>, val pins: Map<String, String>, val changed: Boolean)

    /** What the old key signs to hand over to [key]. */
    fun rotationMessage(key: ReceiptPublicKey): ByteArray = (ROTATION_CONTEXT + key.toRaw().toHex()).encodeToByteArray()

    /** Whether [signature] (base64 Ed25519) is the rotation of [key] signed by [by]. Anything unreadable is refused. */
    fun verifyRotation(key: ReceiptPublicKey, signature: String, by: ReceiptPublicKey): Boolean {
        val bytes = try {
            base64.decode(signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (bytes.size != SIGNATURE_BYTES) return false
        return try {
            by.verify(rotationMessage(key), bytes)
        } catch (_: Exception) {
            false
        }
    }

    /** A served Ed25519 key whose id is the one announced; null for any other (a server naming one key and serving another). */
    fun usable(served: ServedReceiptKey): ReceiptPublicKey? {
        if (served.alg.lowercase() != "ed25519") return null
        return try {
            ReceiptPublicKey.fromSpkiPem(served.publicKeyPem).takeIf { it.kid == served.kid }
        } catch (_: Exception) {
            null
        }
    }

    /** [kept]: the pins of the server, empty before its first view, null when they could not be read (nothing trusted). */
    fun trust(served: List<ServedReceiptKey>, kept: Map<String, String>?): Trust {
        if (kept == null || kept.values.any { !RAW_KEY_HEX.matches(it) }) return Trust(emptyList(), kept.orEmpty(), changed = false)
        val candidates = served.mapNotNull { candidate -> usable(candidate)?.let { candidate to it } }
        val firstView = kept.isEmpty()
        val trusted = kept.toMutableMap()
        if (firstView) for ((_, key) in candidates) trusted.getOrPut(key.kid) { key.toRaw().toHex() }
        var grew = !firstView
        while (grew) {
            grew = false
            for ((candidate, key) in candidates) {
                val rotation = candidate.rotation ?: continue
                if (key.kid in trusted) continue
                val by = trusted[rotation.kid]?.let(::publicKeyOf) ?: continue
                if (!verifyRotation(key, rotation.signature, by)) continue
                trusted[key.kid] = key.toRaw().toHex()
                grew = true
            }
        }
        val keys = candidates.map { it.second }.filter { trusted[it.kid] == it.toRaw().toHex() }.distinctBy { it.kid }
        return Trust(keys, trusted, changed = trusted.size > kept.size)
    }

    private fun publicKeyOf(rawHex: String): ReceiptPublicKey? = try {
        ReceiptPublicKey.fromRaw(rawHex.hexToBytes())
    } catch (_: Exception) {
        null
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
