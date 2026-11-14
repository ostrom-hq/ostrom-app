// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/services/PushPlatform.kt; see NOTICE.
package run.ostrom.app.domain.services

import kotlinx.coroutines.flow.StateFlow
import run.ostrom.app.domain.models.PushMessage

/**
 * The push side of a phone (`notifications.push.v1`): the installation's FCM token and its P-256 key pair. The private
 * key never leaves the device's secure store (the Android Keystore-sealed credential store, the iOS Keychain shared
 * with the notification service extension); servers encrypt each notification for the public key. Absent on the
 * desktop, and in a phone build without a Firebase project.
 */
interface PushPlatform {
    /** `ios` or `android`. */
    val platform: String

    /** The phone's language (`fr-FR`), for the text of the notifications; null when the system has none. */
    val locale: String?

    /** The installation's current FCM token: null until the system gave one. */
    val token: StateFlow<String?>

    /** The public key (SubjectPublicKeyInfo, DER, base64), made on first use and the same afterwards. */
    suspend fun publicKey(): String

    /** Decrypts and reads a push's payload (`PushPayload`); throws when it is not for this key. */
    suspend fun open(payload: String): PushMessage

    /**
     * The organization and member of every account signed in on this phone, each as `orgId memberId`, at each change.
     * For a reader outside the app (the iOS notification service extension), which shows a push's text only for one of
     * them, as [run.ostrom.app.domain.usecases.push.ReadPushUseCase] does in the app. Nothing by default.
     */
    suspend fun signedIn(accounts: Set<String>) {}
}
