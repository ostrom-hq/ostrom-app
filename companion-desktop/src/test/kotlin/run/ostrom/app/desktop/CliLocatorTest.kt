// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/src/test/.../CliLocatorTest.kt; see NOTICE.
package run.ostrom.app.desktop

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Runs nothing: answers each executable as [answers] says. */
class FakeProcesses(val answers: MutableMap<List<String>, CliResult> = mutableMapOf()) : CliProcesses {
    val runs = mutableListOf<List<String>>()
    var streamed: List<Pair<String, Boolean>> = emptyList()
    var streamCode: Int? = 0
    var error: Exception? = null

    override suspend fun run(executable: String, arguments: List<String>, timeoutMillis: Long): CliResult {
        runs += listOf(executable) + arguments
        error?.let { throw it }
        return answers[listOf(executable) + arguments] ?: CliResult(1, "", "unknown")
    }

    override suspend fun stream(executable: String, arguments: List<String>, timeoutMillis: Long, line: (String, Boolean) -> Unit): Int? {
        runs += listOf(executable) + arguments
        streamed.forEach { (text, error) -> line(text, error) }
        return streamCode
    }
}

/** An executable file named ostrom in [directory]. */
fun executable(directory: File): String = File(directory, "ostrom").apply {
    parentFile.mkdirs()
    writeText("#!/bin/sh\n")
    setExecutable(true)
}.path

class CliLocatorTest {
    @Test
    fun theVersionIsReadFromOstromVersion() {
        assertEquals("0.4.1", CliLocator.parseVersion("ostrom 0.4.1 (kotlin-native, macOS arm64)\n"))
        assertEquals("1.0.0-rc.2", CliLocator.parseVersion("ostrom 1.0.0-rc.2 (kotlin-native, Linux x64)"))
        assertNull(CliLocator.parseVersion("TeamCode 1.2.3"))
        assertNull(CliLocator.parseVersion("ostrom version unknown"))
    }

    @Test
    fun theInstallersFoldersComeBeforeThePath() {
        val mac = DesktopEnvironment(mapOf("HOME" to "/Users/ada", "PATH" to "/usr/bin:/opt/homebrew/bin"), os = DesktopEnvironment.Os.MACOS)
        assertEquals(
            listOf("/Users/ada/.ostrom/bin/ostrom", "/opt/homebrew/bin/ostrom", "/usr/local/bin/ostrom", "/usr/bin/ostrom"),
            CliLocator(mac, FakeProcesses()).candidates(),
        )
        val custom = DesktopEnvironment(mapOf("HOME" to "/home/ada", "OSTROM_INSTALL_DIR" to "/opt/ostrom/"), mapOf("PATH" to "/home/ada/bin"), DesktopEnvironment.Os.LINUX)
        assertEquals(
            listOf("/opt/ostrom/ostrom", "/home/linuxbrew/.linuxbrew/bin/ostrom", "/usr/local/bin/ostrom", "/home/ada/bin/ostrom"),
            CliLocator(custom, FakeProcesses()).candidates(),
            "OSTROM_INSTALL_DIR, then the login shell's PATH",
        )
        val windows = DesktopEnvironment(mapOf("USERPROFILE" to "C:\\Users\\ada"), os = DesktopEnvironment.Os.WINDOWS)
        assertEquals(listOf("C:\\Users\\ada\\.ostrom\\bin\\ostrom.exe", "C:\\Users\\ada\\scoop\\shims\\ostrom.exe"), CliLocator(windows, FakeProcesses()).candidates())
    }

    @Test
    fun theFirstCliThatAnswersIsUsed() = withTemporaryDirectory { directory ->
        runTest {
            val broken = executable(File(directory, "broken"))
            val good = executable(File(directory, "good"))
            val environment = DesktopEnvironment(mapOf("HOME" to directory.path, "PATH" to listOf(File(directory, "missing").path, File(directory, "broken").path, File(directory, "good").path).joinToString(File.pathSeparator)))
            val processes = FakeProcesses(mutableMapOf(listOf(good, "version") to CliResult(0, "ostrom 0.4.1 (kotlin-native, macOS arm64)\n", "")))
            assertEquals(CliInstallation(good, "0.4.1"), CliLocator(environment, processes).find())
            // Homebrew's folders may hold a real CLI on the machine running the test: only this test's are counted.
            assertEquals(listOf(listOf(broken, "version"), listOf(good, "version")), processes.runs.filter { it.first().startsWith(directory.path) })
            assertNull(CliLocator(DesktopEnvironment(mapOf("HOME" to directory.path)), processes).find())
        }
    }

    @Test
    fun aCancelledSearchPropagates() = withTemporaryDirectory { directory ->
        runTest {
            executable(File(directory, "bin"))
            val processes = FakeProcesses().apply { error = CancellationException("closed") }
            val environment = DesktopEnvironment(mapOf("HOME" to directory.path, "PATH" to File(directory, "bin").path))
            assertFailsWith<CancellationException> { CliLocator(environment, processes).find() }
        }
    }
}
