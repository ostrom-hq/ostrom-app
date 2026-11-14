// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/src/test/.../DesktopEnvironmentTest.kt; see NOTICE.
package run.ostrom.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopEnvironmentTest {
    @Test
    fun theCliFolderIsResolvedAsTheCliResolvesIt() {
        fun folder(os: DesktopEnvironment.Os, vararg variables: Pair<String, String>) = DesktopEnvironment(mapOf(*variables), os = os).cliDirectory
        assertEquals("/tmp/o", folder(DesktopEnvironment.Os.MACOS, "OSTROM_HOME" to "/tmp/o", "HOME" to "/Users/ada"))
        assertEquals("/x/ostrom", folder(DesktopEnvironment.Os.LINUX, "XDG_CONFIG_HOME" to "/x", "HOME" to "/home/ada"))
        assertEquals("/home/ada/.config/ostrom", folder(DesktopEnvironment.Os.LINUX, "HOME" to "/home/ada", "XDG_CONFIG_HOME" to ""))
        assertEquals("C:\\Users\\ada\\AppData\\Roaming\\ostrom", folder(DesktopEnvironment.Os.WINDOWS, "APPDATA" to "C:\\Users\\ada\\AppData\\Roaming"))
        assertEquals("C:\\Users\\ada\\AppData\\Roaming\\ostrom", folder(DesktopEnvironment.Os.WINDOWS, "USERPROFILE" to "C:\\Users\\ada"))
        assertNull(folder(DesktopEnvironment.Os.LINUX))
    }

    @Test
    fun theLoginShellFillsWhatTheProcessLacks() {
        val environment = DesktopEnvironment(
            mapOf("PATH" to "/usr/bin", "HOME" to "/Users/ada"),
            DesktopEnvironment.parseLoginShell("profile noise\n\n__ostrom_environment__\n__ostrom_environment__/Users/ada/.cfg\n__ostrom_environment__/opt/homebrew/bin:/usr/bin\n"),
            DesktopEnvironment.Os.MACOS,
        )
        assertEquals("/Users/ada/.cfg/ostrom", environment.cliDirectory)
        assertEquals(listOf("/usr/bin", "/opt/homebrew/bin"), environment.path)
        assertEquals(
            mapOf("PATH" to listOf("/usr/bin", "/opt/homebrew/bin").joinToString(File.pathSeparator), "XDG_CONFIG_HOME" to "/Users/ada/.cfg"),
            environment.cliVariables(),
            "The CLI the app runs reads the same folder",
        )
    }

    @Test
    fun anUnexpectedShellOutputIsIgnored() {
        assertEquals(emptyMap(), DesktopEnvironment.parseLoginShell("nothing"))
        assertEquals(emptyMap(), DesktopEnvironment.parseLoginShell("__ostrom_environment__relative\n__ostrom_environment__\n__ostrom_environment__\n"))
    }
}
