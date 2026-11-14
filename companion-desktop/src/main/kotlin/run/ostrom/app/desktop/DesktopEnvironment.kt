// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../DesktopEnvironment.kt; see NOTICE.
package run.ostrom.app.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The environment the app shares with the `ostrom` CLI. An app opened from the Finder or a desktop launcher inherits
 * neither the shell's `PATH` nor its `OSTROM_HOME` or `XDG_CONFIG_HOME`: without care, the CLI and the app would read two
 * different folders. So the app reads them once from a login shell ([loginShell]), and the CLI it runs gets the same
 * values back.
 */
class DesktopEnvironment(
    private val process: Map<String, String>,
    private val loginShell: Map<String, String> = emptyMap(),
    val os: Os = Os.current(),
) {
    enum class Os {
        MACOS, LINUX, WINDOWS;

        companion object {
            fun current(name: String = System.getProperty("os.name").orEmpty()): Os = when {
                name.startsWith("Mac", ignoreCase = true) -> MACOS
                name.startsWith("Windows", ignoreCase = true) -> WINDOWS
                else -> LINUX
            }
        }
    }

    /** A variable of the process, else of the login shell; an empty one counts as unset, as for the CLI. */
    operator fun get(name: String): String? = process[name]?.takeIf { it.isNotEmpty() } ?: loginShell[name]?.takeIf { it.isNotEmpty() }

    /** The user's home folder, as the CLI reads it. */
    val home: String? get() = if (os == Os.WINDOWS) get("USERPROFILE") ?: get("HOME") else get("HOME")

    /**
     * The CLI's folder, resolved as ostrom-cli's `Environment.configDirectory`: `$OSTROM_HOME`, else
     * `%APPDATA%\ostrom` on Windows, else `$XDG_CONFIG_HOME/ostrom`, else `~/.config/ostrom`. Null when no home is known.
     */
    val cliDirectory: String?
        get() {
            val override = get("OSTROM_HOME")
            val appData = get("APPDATA")
            val xdg = get("XDG_CONFIG_HOME")
            val home = home
            return when {
                override != null -> override
                os == Os.WINDOWS && appData != null -> "$appData\\ostrom"
                os == Os.WINDOWS && home != null -> "$home\\AppData\\Roaming\\ostrom"
                os == Os.WINDOWS -> null
                xdg != null -> "$xdg/ostrom"
                home != null -> "$home/.config/ostrom"
                else -> null
            }
        }

    /** Every `PATH` entry, the process's first, then those only the login shell adds. */
    val path: List<String>
        get() = listOfNotNull(process["PATH"], loginShell["PATH"])
            .flatMap { it.split(File.pathSeparatorChar) }.filter { it.isNotBlank() }.distinct()

    /** What the CLI the app starts is given on top of the app's own environment: the merged `PATH`, and the folder variables. */
    fun cliVariables(): Map<String, String> = buildMap {
        put("PATH", path.joinToString(File.pathSeparator))
        for (name in FOLDER_VARIABLES) this@DesktopEnvironment[name]?.let { put(name, it) }
    }

    companion object {
        private const val MARKER = "__ostrom_environment__"

        /** The variables that say where the CLI's folder is, read from the login shell. */
        private val FOLDER_VARIABLES = listOf("OSTROM_HOME", "XDG_CONFIG_HOME")

        /** The app's environment: its process, and on macOS and Linux a login shell read once. */
        fun detect(process: Map<String, String> = System.getenv()): DesktopEnvironment {
            val os = Os.current()
            // Started from a terminal, the app already has the shell's variables: no shell to start.
            val shell = if (os == Os.WINDOWS || process["TERM_PROGRAM"] != null) emptyMap()
            else readLoginShell(process["SHELL"]?.takeIf { File(it).isAbsolute } ?: "/bin/sh")
            return DesktopEnvironment(process, shell, os)
        }

        /**
         * `OSTROM_HOME`, `XDG_CONFIG_HOME` and `PATH` as a login shell sets them, bounded to [timeoutMillis]; empty when
         * the shell fails or is slow. Only these values are read, between markers, so a profile that prints does no harm.
         */
        fun readLoginShell(shell: String, timeoutMillis: Long = 3_000): Map<String, String> = try {
            val script = "printf '\\n$MARKER%s\\n$MARKER%s\\n$MARKER%s\\n' \"\${OSTROM_HOME-}\" \"\${XDG_CONFIG_HOME-}\" \"\${PATH-}\""
            val process = ProcessBuilder(shell, "-l", "-c", script)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = StringBuilder()
            val reader = Thread { output.append(process.inputStream.bufferedReader().readText().take(MAX_SHELL_OUTPUT)) }.apply { isDaemon = true; start() }
            // A shell still running after the delay is left to finish on its own; nothing waits for it, nothing signals it.
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) emptyMap() else {
                reader.join(500)
                parseLoginShell(output.toString())
            }
        } catch (_: Exception) {
            // not coroutine-reachable: read once in main before anything starts.
            emptyMap()
        }

        private const val MAX_SHELL_OUTPUT = 65_536

        internal fun parseLoginShell(output: String): Map<String, String> {
            val values = output.lines().filter { it.startsWith(MARKER) }.map { it.removePrefix(MARKER) }
            if (values.size != 3) return emptyMap()
            return buildMap {
                values[0].takeIf { it.isNotBlank() && File(it).isAbsolute }?.let { put("OSTROM_HOME", it) }
                values[1].takeIf { it.isNotBlank() && File(it).isAbsolute }?.let { put("XDG_CONFIG_HOME", it) }
                values[2].takeIf { it.isNotBlank() }?.let { put("PATH", it) }
            }
        }
    }
}
