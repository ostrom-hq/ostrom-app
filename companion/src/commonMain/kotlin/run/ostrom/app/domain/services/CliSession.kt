// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.services

/**
 * The `ostrom` CLI of this computer, as the desktop's CLI account ([run.ostrom.app.domain.models.Account.CLI_ACCOUNT_ID])
 * uses it. The app never writes the CLI's files or its secret: what changes the CLI's sign-in is the CLI's own command.
 * Only the desktop binds it.
 */
interface CliSession {
    /**
     * Signs the CLI out of [origin] with `ostrom logout --server <origin>`, which revokes its session and forgets its
     * token. False when no CLI was found or the command failed: the caller then revokes the session itself.
     */
    suspend fun signOut(origin: String): Boolean
}
