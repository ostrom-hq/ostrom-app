// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion androidHostTest PushCipherTest.kt (Apache-2.0); see NOTICE.
package run.ostrom.app

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.PushMessage
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.push.AndroidPushPlatform
import run.ostrom.app.infrastructure.push.PushCipher

/**
 * The Android side of the push payload, against payloads sealed the way ostrom-server's `EciesPushEncryption` seals
 * them: [seal] follows the documented bytes on its own (`PushPayload`), with the JDK's primitives.
 */
class PushCipherTest {
    private val plaintext = """{"v":1,"id":"n1","origin":"https://ostrom.example.org","orgId":"o1","memberId":"m1","kind":"approval",""" +
        """"title":"Deploy to production","body":"ada asks for your yes","approvalId":"ap1","at":1700000000000}"""

    @Test
    fun aSealedPayloadDecryptsWithTheDeviceKey() {
        val phone = PushCipher.generate()
        val public = phone.public as ECPublicKey
        assertEquals(plaintext, PushCipher.decrypt(phone.private, public, seal(public, plaintext)))
    }

    @Test
    fun anotherKeyOrADamagedPayloadIsRefused() {
        val phone = PushCipher.generate()
        val public = phone.public as ECPublicKey
        val sealed = seal(public, plaintext)
        val other = PushCipher.generate()
        assertFails { PushCipher.decrypt(other.private, other.public as ECPublicKey, sealed) }
        val bytes = Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val damaged = Base64.getEncoder().encodeToString(bytes)
        val otherVersion = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(sealed).also { it[0] = 2 })
        for (payload in listOf(damaged, otherVersion, "", "AQ==", sealed.drop(8))) {
            assertFails(payload) { PushCipher.decrypt(phone.private, public, payload) }
        }
    }

    @Test
    fun thePlatformOpensAPushWithTheStoredKey() = runTest {
        val store = MemoryCredentialStore()
        val platform = AndroidPushPlatform(store)
        val public = PushCipher.decodePublic(platform.publicKey())
        val push = AndroidPushPlatform(store).open(seal(public, plaintext))
        assertEquals(PushMessage("n1", "https://ostrom.example.org", "o1", "m1", "approval", "Deploy to production", "ada asks for your yes", "ap1"), push)
    }

    @Test
    fun theKeyPairIsMadeOnceAndKeptInTheCredentialStore() = runTest {
        val store = MemoryCredentialStore()
        val first = AndroidPushPlatform(store).publicKey()
        assertEquals(first, AndroidPushPlatform(store).publicKey()) // A restarted app keeps its key.
        assertTrue(store.read(AndroidPushPlatform.KEY_ENTRY)!!.endsWith(" $first"))
        store.delete(AndroidPushPlatform.KEY_ENTRY)
        assertNotEquals(first, AndroidPushPlatform(store).publicKey())
        // A damaged entry is replaced by a new pair.
        store.write(AndroidPushPlatform.KEY_ENTRY, "not a key")
        assertNotEquals(first, AndroidPushPlatform(store).publicKey())
    }

    /** `base64(0x01 | ephemeral point | nonce | AES-256-GCM(HKDF-SHA256(ECDH, ephemeral | phone, "ostrom-push-v1")))`. */
    private fun seal(phone: ECPublicKey, text: String): String {
        val ephemeral = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val secret = KeyAgreement.getInstance("ECDH").run { init(ephemeral.private); doPhase(phone, true); generateSecret() }
        val ephemeralPoint = point(ephemeral.public as ECPublicKey)
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(ephemeralPoint + point(phone), "HmacSHA256")); doFinal(secret) }
        val key = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            doFinal("ostrom-push-v1".encodeToByteArray() + byteArrayOf(1))
        }
        val nonce = ByteArray(12) { (it * 7).toByte() }
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(text.encodeToByteArray())
        }
        return Base64.getEncoder().encodeToString(byteArrayOf(1) + ephemeralPoint + nonce + sealed)
    }

    private fun point(key: ECPublicKey): ByteArray {
        fun fixed(value: BigInteger) = value.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }
}
