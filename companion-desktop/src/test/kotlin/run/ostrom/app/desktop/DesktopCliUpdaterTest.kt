// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/src/test/.../DesktopAppUpdaterTest.kt; see NOTICE.
package run.ostrom.app.desktop

import java.io.File
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.services.CliUpdateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCliUpdaterTest {
    private class Fixture(directory: File) {
        val cli = executable(File(directory, "bin"))
        val environment = DesktopEnvironment(mapOf("HOME" to directory.path, "PATH" to File(directory, "bin").path))
        val processes = FakeProcesses(mutableMapOf(listOf(cli, "version") to CliResult(0, "ostrom 0.4.1 (kotlin-native, macOS arm64)\n", "")))
        val locator = CliLocator(environment, processes)
        val updater = DesktopCliUpdater(locator, processes)
        val session = DesktopCliSession(locator, processes)

        fun answer(vararg arguments: String, result: CliResult) {
            processes.answers[listOf(cli) + arguments] = result
        }
    }

    @Test
    fun theCheckReadsTheTwoLinesOfOstromUpdateCheck() {
        assertEquals("0.4.1", DesktopCliUpdater.parseCheck("✓ ostrom 0.4.1 is up to date.\n"))
        assertEquals("0.5.0", DesktopCliUpdater.parseCheck("! ostrom 0.5.0 is available. Run ostrom update.\n"))
        assertEquals("1.0.0-rc.1", DesktopCliUpdater.parseCheck("ostrom: reading the release\n! ostrom 1.0.0-rc.1 is available. Run ostrom update --version 1.0.0-rc.1.\n"))
        assertNull(DesktopCliUpdater.parseCheck(""))
        assertNull(DesktopCliUpdater.parseCheck("Something new the CLI says"))
        assertNull(DesktopCliUpdater.parseCheck("! ostrom ../../evil is available. Run ostrom update."))
        assertNull(DesktopCliUpdater.parseCheck("✓ ostrom 0.4.1 is already installed."))
    }

    @Test
    fun theLatestVersionComesFromTheInstalledCli() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.answer("update", "--check", result = CliResult(0, "! ostrom 0.5.0 is available. Run ostrom update.\n", ""))
            assertEquals("0.4.1", f.updater.installedVersion())
            assertEquals("0.5.0", f.updater.latestVersion())
        }
    }

    @Test
    fun aCheckThatFailsOrSaysSomethingElseIsAnError() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.answer("update", "--check", result = CliResult(0, "Unexpected\n", ""))
            assertEquals(CliUpdateException.Reason.UNEXPECTED_OUTPUT, assertFailsWith<CliUpdateException> { f.updater.latestVersion() }.reason)
            f.answer("update", "--check", result = CliResult(69, "", "ostrom: GitHub could not be reached\n"))
            val failed = assertFailsWith<CliUpdateException> { f.updater.latestVersion() }
            assertEquals(CliUpdateException.Reason.FAILED, failed.reason)
            assertEquals(listOf("ostrom: GitHub could not be reached"), failed.lines)
            f.answer("update", "--check", result = CliResult(null, "", ""))
            assertEquals(CliUpdateException.Reason.STILL_RUNNING, assertFailsWith<CliUpdateException> { f.updater.latestVersion() }.reason)
        }
    }

    @Test
    fun withoutACliNothingRuns() = runTest {
        val environment = DesktopEnvironment(mapOf("HOME" to "/nonexistent-home"), os = DesktopEnvironment.Os.WINDOWS)
        val processes = FakeProcesses()
        val updater = DesktopCliUpdater(CliLocator(environment, processes), processes)
        assertNull(updater.installedVersion())
        assertEquals(CliUpdateException.Reason.NOT_INSTALLED, assertFailsWith<CliUpdateException> { updater.latestVersion() }.reason)
        assertEquals(CliUpdateException.Reason.NOT_INSTALLED, assertFailsWith<CliUpdateException> { updater.update { } }.reason)
        assertFalse(DesktopCliSession(CliLocator(environment, processes), processes).signOut("https://api.example"))
        assertTrue(processes.runs.isEmpty())
    }

    @Test
    fun theUpdateStreamsItsLinesThenReadsTheNewVersion() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.processes.streamed = listOf("Downloading ostrom 0.5.0" to false, "" to false, "✓ ostrom 0.5.0 installed." to false)
            val lines = mutableListOf<String>()
            f.processes.answers[listOf(f.cli, "version")] = CliResult(0, "ostrom 0.4.1\n", "")
            val installed = f.updater.update { line ->
                lines += line
                f.processes.answers[listOf(f.cli, "version")] = CliResult(0, "ostrom 0.5.0 (kotlin-native, macOS arm64)\n", "")
            }
            assertEquals("0.5.0", installed)
            assertEquals(listOf("Downloading ostrom 0.5.0", "✓ ostrom 0.5.0 installed."), lines)
            assertTrue(listOf(f.cli, "update") in f.processes.runs)
        }
    }

    @Test
    fun aFailedUpdateGivesTheCliLastLines() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            f.processes.streamed = (1..6).map { "line $it" to (it % 2 == 0) }
            f.processes.streamCode = 3
            val failed = assertFailsWith<CliUpdateException> { f.updater.update { } }
            assertEquals(CliUpdateException.Reason.FAILED, failed.reason)
            assertEquals(listOf("line 3", "line 4", "line 5", "line 6"), failed.lines)
            f.processes.streamCode = null
            assertEquals(CliUpdateException.Reason.STILL_RUNNING, assertFailsWith<CliUpdateException> { f.updater.update { } }.reason)
        }
    }

    @Test
    fun signingOutRunsTheCliOwnLogout() = withTemporaryDirectory { directory ->
        runTest {
            val f = Fixture(directory)
            assertFalse(f.session.signOut("https://api.example"), "A logout that failed")
            f.answer("logout", "--server", "https://api.example", result = CliResult(0, "✓ Signed out @ada.\n", ""))
            assertTrue(f.session.signOut("https://api.example"))
            assertTrue(listOf(f.cli, "logout", "--server", "https://api.example") in f.processes.runs)
        }
    }
}
