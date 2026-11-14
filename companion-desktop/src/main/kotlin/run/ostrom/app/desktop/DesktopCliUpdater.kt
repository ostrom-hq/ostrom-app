// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../DesktopAppUpdater.kt; see NOTICE.
package run.ostrom.app.desktop

import run.ostrom.app.domain.services.CliUpdateException
import run.ostrom.app.domain.services.CliUpdateException.Reason
import run.ostrom.app.domain.services.CliUpdater
import run.ostrom.app.domain.usecases.updates.UpdatePolicy

/**
 * The update banner's work, done by the installed CLI so that every release goes through the CLI's own signature checks
 * (the app never downloads or verifies a release itself): the check is `ostrom update --check`, the update
 * `ostrom update`. The app only reads the CLI's output; it never signals the CLI.
 */
class DesktopCliUpdater(private val locator: CliLocator, private val processes: CliProcesses) : CliUpdater {
    override suspend fun installedVersion(): String? = locator.find()?.version

    override suspend fun latestVersion(): String {
        val cli = locator.find() ?: throw CliUpdateException(Reason.NOT_INSTALLED)
        val result = processes.run(cli.executable, listOf("update", "--check"), CHECK_TIMEOUT_MILLIS)
        val lines = lastLines(result.stdout + "\n" + result.stderr)
        return when (result.exitCode) {
            null -> throw CliUpdateException(Reason.STILL_RUNNING)
            0 -> parseCheck(result.stdout) ?: throw CliUpdateException(Reason.UNEXPECTED_OUTPUT, lines)
            else -> throw CliUpdateException(Reason.FAILED, lines)
        }
    }

    override suspend fun update(output: (String) -> Unit): String? {
        val cli = locator.find() ?: throw CliUpdateException(Reason.NOT_INSTALLED)
        val last = ArrayDeque<String>()
        // `ostrom update` says why it stopped on standard output: the last lines of both streams explain a failure.
        val code = processes.stream(cli.executable, listOf("update"), UPDATE_TIMEOUT_MILLIS) { line, _ ->
            if (line.isBlank()) return@stream
            output(line)
            synchronized(last) { last.addLast(line); if (last.size > MAX_ERROR_LINES) last.removeFirst() }
        }
        val lines = synchronized(last) { last.toList() }
        when (code) {
            null -> throw CliUpdateException(Reason.STILL_RUNNING, lines)
            0 -> Unit
            else -> throw CliUpdateException(Reason.FAILED, lines)
        }
        return locator.find()?.version
    }

    companion object {
        private const val CHECK_TIMEOUT_MILLIS = 60_000L
        private const val UPDATE_TIMEOUT_MILLIS = 20 * 60_000L
        private const val MAX_ERROR_LINES = 4

        private val UP_TO_DATE = Regex("""^✓ ostrom (\S+) is up to date\.$""")
        private val AVAILABLE = Regex("""^! ostrom (\S+) is available\. Run .*$""")

        /**
         * The latest release in what `ostrom update --check` printed (ostrom-cli's `SelfUpdate`): `✓ ostrom X is up to
         * date.` gives X, `! ostrom Y is available. Run ostrom update.` gives Y. Null for anything else, never a crash.
         */
        fun parseCheck(stdout: String): String? = stdout.lineSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
            (UP_TO_DATE.matchEntire(line) ?: AVAILABLE.matchEntire(line))?.groupValues?.get(1)?.takeIf(UpdatePolicy::isVersion)
        }

        private fun lastLines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_ERROR_LINES).map { it.take(MAX_LINE) }

        private const val MAX_LINE = 2_000
    }
}
