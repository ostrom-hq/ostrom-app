// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion domain/services/AppUpdater.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.services

/**
 * Updates of the `ostrom` CLI installed on this computer, through the CLI itself (`ostrom update --check`, then
 * `ostrom update`): the CLI authenticates every release with its own pinned keys, the app never downloads or verifies
 * one. The app has no published release of its own yet, so it does not update itself.
 *
 * Only the desktop binds it: the phones have no CLI, and the update banner never shows there.
 */
interface CliUpdater {
    /** The version the installed CLI reports (`ostrom version`); null when no CLI answers. No network. */
    suspend fun installedVersion(): String?

    /**
     * The latest signed release as the installed CLI read it (`ostrom update --check`): the CLI's own version when it is
     * up to date. Throws a [CliUpdateException] on any failure (no CLI, offline, an output it does not recognize): the
     * caller stays silent.
     */
    suspend fun latestVersion(): String

    /**
     * Runs `ostrom update`, each of its lines given to [output] as it comes; returns the version the CLI reports after
     * it, null when it no longer answers. A refusal is a [CliUpdateException].
     */
    suspend fun update(output: (String) -> Unit): String?
}

/** Why the CLI could not be checked or updated; [lines] are the CLI's own last lines, shown as they are. */
class CliUpdateException(val reason: Reason, val lines: List<String> = emptyList()) : Exception(reason.name) {
    enum class Reason {
        /** No `ostrom` answers on this computer. */
        NOT_INSTALLED,

        /** The command still ran after its delay: it is left to finish on its own. */
        STILL_RUNNING,

        /** The command stopped with an error. */
        FAILED,

        /** The command printed something this app does not recognize. */
        UNEXPECTED_OUTPUT,
    }
}
