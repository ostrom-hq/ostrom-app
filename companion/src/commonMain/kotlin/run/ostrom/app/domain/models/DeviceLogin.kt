// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.models

/**
 * A device-flow sign-in waiting for the person: [userCode] is entered on [verificationUri], which is GitHub's page or
 * one of the server's own origin (never a look-alike, the CLI's rule). [deviceCode] is what the app polls with; it is
 * never shown.
 */
class DeviceLogin(
    val server: ServerInfo,
    val userCode: String,
    val verificationUri: String,
    internal val deviceCode: String,
    val intervalMillis: Long,
    val expiresInMillis: Long,
) {
    override fun toString(): String = "DeviceLogin(${server.origin}, $verificationUri)"
}
