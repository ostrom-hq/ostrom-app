// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/repositories/PinnedReceiptsRepository.ts.
package run.ostrom.app.infrastructure.credentials

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import run.ostrom.app.domain.repositories.ReceiptKeyPinRepository
import run.ostrom.app.domain.services.CredentialStore

/**
 * The receipt key pins in the platform's secure store (Keychain, Android Keystore, the desktop keyring), one entry per
 * server origin: a JSON object of raw keys in hex by key id. An entry that is not such an object reads as unreadable,
 * and the history then trusts no key of that server (fail closed).
 */
class SecureReceiptKeyPinRepository(private val store: CredentialStore) : ReceiptKeyPinRepository {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun read(origin: String): Map<String, String>? {
        val text = store.read(entry(origin)) ?: return emptyMap()
        return try {
            Json.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun write(origin: String, pins: Map<String, String>) = store.write(entry(origin), Json.encodeToString(serializer, pins))

    companion object {
        /** The store's entry of a server's pins; account ids are UUIDs (or `cli`, `demo`), so it never names an account. */
        fun entry(origin: String): String = "receipt-pins:$origin"
    }
}
