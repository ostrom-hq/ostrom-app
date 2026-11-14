// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../CliLocator.kt; see NOTICE.
package run.ostrom.app.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** An `ostrom` CLI found on this computer, and the version it reports (`ostrom version`). */
data class CliInstallation(val executable: String, val version: String)

/** What a CLI run gave: its exit code (null when it was still running after the delay) and its bounded output. */
data class CliResult(val exitCode: Int?, val stdout: String, val stderr: String)

/** Runs the CLI; an interface so that the updater and the sign-out are tested without a CLI. */
interface CliProcesses {
    /** Runs [executable] with [arguments] and returns its result, or a null exit code after [timeoutMillis]. */
    suspend fun run(executable: String, arguments: List<String>, timeoutMillis: Long): CliResult

    /**
     * Runs it like [run], passing each line of its output on as it comes ([line] gets the text and whether it came
     * from standard error). Returns the exit code, or null when it still runs after the delay.
     */
    suspend fun stream(executable: String, arguments: List<String>, timeoutMillis: Long, line: (String, Boolean) -> Unit): Int?
}

/**
 * Runs the CLI with an argument list, never through a shell, with the app's environment plus
 * [DesktopEnvironment.cliVariables] (the same CLI folder, the login shell's `PATH`). Standard input is closed and each
 * output is kept to its first [MAX_OUTPUT] characters. A run still going after its delay is left to finish on its own:
 * the app never signals the CLI.
 */
class SystemCliProcesses(private val environment: DesktopEnvironment) : CliProcesses {
    override suspend fun run(executable: String, arguments: List<String>, timeoutMillis: Long): CliResult = withContext(Dispatchers.IO) {
        val process = start(executable, arguments)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val readers = listOf(process.inputStream to stdout, process.errorStream to stderr).map { (stream, into) ->
            Thread {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8_192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(into) { if (into.length < MAX_OUTPUT) into.append(buffer, 0, minOf(read, MAX_OUTPUT - into.length)) }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        val exited = runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
        if (exited) readers.forEach { it.join(1_000) }
        CliResult(if (exited) process.exitValue() else null, synchronized(stdout) { stdout.toString() }, synchronized(stderr) { stderr.toString() })
    }

    override suspend fun stream(executable: String, arguments: List<String>, timeoutMillis: Long, line: (String, Boolean) -> Unit): Int? = withContext(Dispatchers.IO) {
        val process = start(executable, arguments)
        val readers = listOf(process.inputStream to false, process.errorStream to true).map { (stream, error) ->
            Thread { stream.bufferedReader().useLines { lines -> lines.forEach { line(it.take(MAX_LINE), error) } } }.apply { isDaemon = true; start() }
        }
        val exited = runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
        if (!exited) return@withContext null
        readers.forEach { it.join(2_000) }
        process.exitValue()
    }

    private fun start(executable: String, arguments: List<String>): Process {
        val builder = ProcessBuilder(listOf(executable) + arguments)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (environment.os == DesktopEnvironment.Os.WINDOWS) "NUL" else "/dev/null")))
        builder.environment().putAll(environment.cliVariables())
        return builder.start()
    }

    private companion object {
        const val MAX_OUTPUT = 262_144
        const val MAX_LINE = 2_000
    }
}

/**
 * Finds the CLI where ostrom-cli's installers put it: `$OSTROM_INSTALL_DIR`, else `~/.ostrom/bin` (`install.sh`,
 * `install.ps1`), Homebrew's and Scoop's folders, then every `PATH` entry, the login shell's included. The first one that
 * answers `ostrom version` is the one the app uses.
 */
class CliLocator(private val environment: DesktopEnvironment, private val processes: CliProcesses) {
    fun candidates(): List<String> {
        val home = environment.home
        val windows = environment.os == DesktopEnvironment.Os.WINDOWS
        val name = if (windows) "ostrom.exe" else "ostrom"
        val separator = if (windows) "\\" else "/"
        val installed = environment["OSTROM_INSTALL_DIR"] ?: home?.let { "$it$separator.ostrom${separator}bin" }
        val managers = when (environment.os) {
            DesktopEnvironment.Os.MACOS -> listOf("/opt/homebrew/bin", "/usr/local/bin")
            DesktopEnvironment.Os.LINUX -> listOf("/home/linuxbrew/.linuxbrew/bin", "/usr/local/bin")
            DesktopEnvironment.Os.WINDOWS -> listOfNotNull(home?.let { "$it\\scoop\\shims" })
        }
        val folders = listOfNotNull(installed) + managers + environment.path
        return folders.map { "${it.trimEnd('/', '\\')}$separator$name" }.distinct()
    }

    /** The first candidate that is an executable file and reports its version; null when none does. */
    suspend fun find(): CliInstallation? {
        for (candidate in candidates()) {
            val file = File(candidate)
            if (!file.isFile || !file.canExecute()) continue
            val result = try {
                processes.run(candidate, listOf("version"), VERSION_TIMEOUT_MILLIS)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            if (result.exitCode != 0) continue
            parseVersion(result.stdout)?.let { return CliInstallation(candidate, it) }
        }
        return null
    }

    companion object {
        private const val VERSION_TIMEOUT_MILLIS = 10_000L

        /** `ostrom 1.2.3 (kotlin-native, macOS arm64)` as `ostrom version` prints it. */
        fun parseVersion(output: String): String? =
            Regex("""^ostrom (\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)(?:\s|$)""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
    }
}
