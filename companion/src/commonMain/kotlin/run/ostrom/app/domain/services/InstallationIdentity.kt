// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/domain/services/InstallationIdentity.kt; see NOTICE.
package run.ostrom.app.domain.services

/**
 * The non-secret identifier of this installation, a random lowercase UUID drawn on first use and kept across sign-outs
 * and servers, so the server lists the app as one device (as the CLI's `installation-id`, `devices.v1`). On a computer
 * it is the CLI's own: the app and the CLI of one machine are one device.
 */
interface InstallationIdentity {
    /** @throws run.ostrom.app.domain.models.AppException.InstallationUnreadable When it cannot be read nor created. */
    suspend fun id(): String
}
