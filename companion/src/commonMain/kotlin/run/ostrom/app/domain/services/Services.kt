// SPDX-License-Identifier: Apache-2.0
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

    /** What a sign-in reports to the server (`ios`, `android`, `macos`, `windows`, `linux`); null for anything else. */
    val reportedPlatform: String? get() = platform.lowercase().takeIf { it in REPORTED_PLATFORMS }

    /**
     * The name this installation's device takes on the server at its first sign-in, such as `Ostrom macOS · studio`: 1
     * to 120 characters without control characters, as the server takes it. A device already registered keeps its name.
     */
    val defaultDeviceName: String
        get() = "Ostrom $platform · $deviceModel".filterNot { it.isISOControl() }.trim().take(MAX_DEVICE_NAME)
            // Never half of a character outside the BMP (an emoji in a host name).
            .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.trimEnd()
}

private val REPORTED_PLATFORMS = setOf("ios", "android", "macos", "windows", "linux")

/** The longest device name the server takes (`DeviceLimits.MAX_NAME`). */
private const val MAX_DEVICE_NAME = 120

/**
 * The system's browser sign-in session (ASWebAuthenticationSession on iOS, a Custom Tab on Android): opens [url] and
 * gives back the address the server redirected to under [callbackScheme], or null when the person closed it. Absent on
 * the desktop, which signs in with the device flow.
 */
fun interface BrowserSignIn {
    suspend fun authenticate(url: String, callbackScheme: String): String?
}
