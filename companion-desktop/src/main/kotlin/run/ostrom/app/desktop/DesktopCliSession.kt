// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import run.ostrom.app.domain.services.CliSession

/**
 * Signs the CLI out with its own command, `ostrom logout --server <origin>`: the CLI revokes its session and forgets
 * its token and its `credentials.json` entry; the app never touches them. False without a CLI, or when it failed.
 */
class DesktopCliSession(private val locator: CliLocator, private val processes: CliProcesses) : CliSession {
    override suspend fun signOut(origin: String): Boolean {
        val cli = locator.find() ?: return false
        return processes.run(cli.executable, listOf("logout", "--server", origin), LOGOUT_TIMEOUT_MILLIS).exitCode == 0
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MILLIS = 60_000L
    }
}
