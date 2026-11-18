// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.infrastructure.repositories.RemoteMemberSetupRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** How an HTTP server's address is shown: the default port of its scheme is not. */
class RemoteMemberSetupRepositoryEdgesTest {
    private val server = ScriptedServer()

    @Test fun aServersAddressIsShownWithoutTheDefaultPortOfItsScheme() = runTest {
        server.signedIn()
        server.json(
            """{"items":[
            {"name":"plain","url":"https://mcp.example.org/mcp","transport":"http"},
            {"name":"explicit","url":"https://mcp.example.org:443/mcp?token=x","transport":"http"},
            {"name":"clear","url":"http://mcp.example.org:80/","transport":"http"},
            {"name":"other","url":"http://mcp.example.org:8080/a","transport":"http"}]}""",
        )
        val shown = RemoteMemberSetupRepository(server.calls).mcpServers("a1").associate { it.name to it.url }
        assertEquals("https://mcp.example.org/mcp", shown["plain"])
        assertEquals("https://mcp.example.org/mcp", shown["explicit"])
        assertEquals("http://mcp.example.org/", shown["clear"])
        assertEquals("http://mcp.example.org:8080/a", shown["other"])
    }
}
