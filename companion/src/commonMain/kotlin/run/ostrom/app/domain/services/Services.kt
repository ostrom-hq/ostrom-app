// SPDX-License-Identifier: AGPL-3.0-only
// The platform's services; Clock, IdGenerator, CredentialStore, PlatformInfo and SecureRandom are shaped like
// TeamCode's companion ones (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.services

fun interface Clock {
    fun nowMillis(): Long
}

fun interface IdGenerator {
    fun newId(): String
}

/** The platform's cryptographically secure random source, for the PKCE verifier and the state of a sign-in. */
fun interface SecureRandom {
    fun nextBytes(count: Int): ByteArray
}

/**
 * Where secrets live, keyed by account id: the Keychain on iOS, an Android Keystore key on Android, the operating
 * system's keyring on the desktop. Never a plain file or a preference.
 */
interface CredentialStore {
    /** False when this device has no usable secure store: secrets are then kept in memory only, until the app quits. */
    val persistent: Boolean
    suspend fun read(accountId: String): String?
    suspend fun write(accountId: String, token: String)
    suspend fun delete(accountId: String)
}

/** The key under which an account's unlock key is kept, beside its token. */
fun unlockKeyEntry(accountId: String): String = "$accountId.unlock"

interface PlatformInfo {
    /** `iOS`, `Android`, `macOS`, `Windows` or `Linux`. */
    val platform: String
    /** The device model or host name. */
    val deviceModel: String
    val appVersion: String

    /** Phones sign in through the browser and hold an unlock key; computers use the device flow. */
    val isMobile: Boolean get() = platform == "iOS" || platform == "Android"
}

/**
 * The system's browser sign-in session (ASWebAuthenticationSession on iOS, a Custom Tab on Android): opens [url] and
 * gives back the address the server redirected to under [callbackScheme], or null when the person closed it. Absent on
 * the desktop, which signs in with the device flow.
 */
fun interface BrowserSignIn {
    suspend fun authenticate(url: String, callbackScheme: String): String?
}
