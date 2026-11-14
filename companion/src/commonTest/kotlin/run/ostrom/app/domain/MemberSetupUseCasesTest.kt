// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.McpProcess
import run.ostrom.app.domain.models.McpTransport
import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.domain.models.PersonalVariant
import run.ostrom.app.domain.repositories.MemberSetupRepository
import run.ostrom.app.domain.usecases.setup.LoadMemberEnvironmentUseCase
import run.ostrom.app.domain.usecases.setup.LoadPersonalMcpServersUseCase
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemberSetupUseCasesTest {
    private val environment = MemberEnvironment(listOf("API_KEY"), listOf("BROKEN"), listOf(PersonalVariant("v1", "API_KEY", "Mine", true, 1L)))
    private val server = PersonalMcpServer("docs", "ostrom-docs", McpTransport.STDIO, null, "npm", "docs@1.0.0", emptyList(), true, McpProcess.Ready)

    private class Repository(private val environment: MemberEnvironment, private val servers: List<PersonalMcpServer>) : MemberSetupRepository {
        val asked = mutableListOf<String>()
        override suspend fun environment(accountId: String): MemberEnvironment { asked += "env"; return environment }
        override suspend fun mcpServers(accountId: String): List<PersonalMcpServer> { asked += "mcp"; return servers }
    }

    @Test
    fun theEnvironmentIsReadOnlyFromAServerThatAnnouncesIt() = runTest {
        val repository = Repository(environment, listOf(server))
        val features = FakeFeatures()
        val load = LoadMemberEnvironmentUseCase(repository, features, fakeGuard())
        val refused = assertFailsWith<AppException.Unsupported> { load("a1") }
        assertEquals(Features.ENVIRONMENT, refused.feature)
        assertTrue(repository.asked.isEmpty())
        features.features = setOf(Features.ENVIRONMENT)
        assertEquals(environment, load("a1"))
    }

    @Test
    fun thePersonalMcpServersAreReadOnlyFromAServerThatAnnouncesThem() = runTest {
        val repository = Repository(environment, listOf(server))
        val features = FakeFeatures(setOf(Features.ENVIRONMENT))
        val load = LoadPersonalMcpServersUseCase(repository, features, fakeGuard())
        assertEquals(Features.MCP_PERSONAL, assertFailsWith<AppException.Unsupported> { load("a1") }.feature)
        features.features = setOf(Features.MCP_PERSONAL)
        assertEquals(listOf(server), load("a1"))
        assertEquals(listOf("mcp"), repository.asked)
    }

    @Test
    fun aTransportAndAProcessStateAreReadFromTheirWireValues() {
        assertEquals(McpTransport.HTTP, McpTransport.parse("http"))
        assertEquals(McpTransport.STDIO, McpTransport.parse("stdio"))
        assertEquals(McpTransport.UNKNOWN, McpTransport.parse("sse"))
        assertEquals(McpProcess.Idle, McpProcess.parse("idle", null))
        assertEquals(McpProcess.Starting, McpProcess.parse("starting", null))
        assertEquals(McpProcess.Ready, McpProcess.parse("ready", null))
        assertEquals(McpProcess.Backoff, McpProcess.parse("backoff", null))
        assertEquals(McpProcess.Failed("start_failed"), McpProcess.parse("failed", "start_failed"))
        assertEquals(McpProcess.Failed(null), McpProcess.parse("failed", "Not a <key>"))
        assertEquals(McpProcess.Untracked, McpProcess.parse("unknown", null))
        assertEquals(McpProcess.Other("stopping"), McpProcess.parse("stopping", null))
        assertEquals(McpProcess.Untracked, McpProcess.parse("Not a key", null))
        assertEquals(McpProcess.Untracked, McpProcess.parse(null, null))
    }
}
