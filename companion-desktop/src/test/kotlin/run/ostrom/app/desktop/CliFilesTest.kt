// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CliFilesTest {
    @Test
    fun theServerIsReadAsTheCliReadsIt() {
        assertEquals("https://api.example", CliFiles.serverOf("server = \"https://api.example\"\n"))
        assertEquals("https://api.example", CliFiles.serverOf("  server='https://api.example' # set by ostrom login"))
        assertEquals("https://api.example/\u00e9", CliFiles.serverOf("server = \"https://api.example/\\u00e9\" # comment"))
        assertEquals("https://b.example", CliFiles.serverOf("server = \"https://a.example\"\nserver = \"https://b.example\""), "The last one wins")
        assertNull(CliFiles.serverOf("# server = \"https://api.example\""))
        assertNull(CliFiles.serverOf("[tools]\nserver = \"https://api.example\""), "A key inside a table is not the CLI's")
        assertNull(CliFiles.serverOf("server = 42"))
        assertNull(CliFiles.serverOf("server = \"https://api.example"), "An unclosed string")
        assertNull(CliFiles.serverOf("server = \"\"\"https://api.example\"\"\""))
        assertNull(CliFiles.serverOf("servers = \"https://api.example\""))
    }

    @Test
    fun aSignInNeedsAnEntryForTheServer() = withTemporaryDirectory { directory ->
        val files = CliFiles(directory.path)
        assertNull(files.signIn(), "No files")
        File(directory, "credentials.json").writeText("""{"https://api.ostrom.run": {"login": "ada"}}""")
        assertEquals(CliSignIn("https://api.ostrom.run"), files.signIn(), "Without config.toml, Ostrom's own server")
        File(directory, "config.toml").writeText("server = \"https://api.example/\"\n")
        assertNull(files.signIn(), "No entry for the server of config.toml")
        File(directory, "credentials.json").writeText("""{"https://api.example": {"login": "ada"}}""")
        assertEquals(CliSignIn("https://api.example"), files.signIn())
        File(directory, "credentials.json").writeText("{ not json")
        assertNull(files.signIn(), "A file the CLI cannot read either")
        File(directory, "credentials.json").writeText("""{"https://api.example": "ada"}""")
        assertNull(files.signIn())
        assertNull(CliFiles(null).signIn(), "No CLI folder")
    }

    @Test
    fun theStampsChangeWithTheFiles() = withTemporaryDirectory { directory ->
        val files = CliFiles(directory.path)
        assertEquals(CliFiles.Stamps(null, null), files.stamps())
        File(directory, "credentials.json").writeText("{}")
        val first = files.stamps()
        File(directory, "credentials.json").writeText("{ }")
        assertNotEquals(first, files.stamps())
    }
}
