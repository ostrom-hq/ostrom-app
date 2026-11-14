// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import run.ostrom.app.domain.models.McpProcess
import run.ostrom.app.domain.models.McpTransport
import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.domain.models.PersonalVariant
import run.ostrom.app.domain.repositories.MemberSetupRepository

private const val HOUR = 3_600_000L

/** The demo member's environment: three names served, one withheld, and one personal variant of their own. */
internal fun demoEnvironment(now: Long) = MemberEnvironment(
    names = listOf("DATABASE_URL", "OPENAI_API_KEY", "SENTRY_DSN"),
    unavailable = listOf("STRIPE_SECRET_KEY"),
    variants = listOf(PersonalVariant("demo-variant-1", "OPENAI_API_KEY", "Ada's key", hasValue = true, updatedAt = now - 26 * HOUR)),
)

/** The demo member's personal MCP servers: a remote one, and a hosted command that is ready. */
internal val demoMcpServers = listOf(
    PersonalMcpServer(
        name = "linear", agentName = "ostrom-linear", transport = McpTransport.HTTP, url = "https://mcp.linear.app/mcp", launcher = null,
        packageName = null, secretNames = listOf("LINEAR_TOKEN"), enabled = true, process = McpProcess.Untracked,
    ),
    PersonalMcpServer(
        name = "notes", agentName = "ostrom-notes", transport = McpTransport.STDIO, url = null, launcher = "npm",
        packageName = "@ada/notes-mcp@1.4.2", secretNames = listOf("NOTES_TOKEN"), enabled = true, process = McpProcess.Ready,
    ),
)

/** The demo account's environment and MCP servers, from memory. */
class DemoMemberSetupRepository(private val demo: DemoMode) : MemberSetupRepository {
    override suspend fun environment(accountId: String): MemberEnvironment = demoEnvironment(demo.store().startedAt)
    override suspend fun mcpServers(accountId: String): List<PersonalMcpServer> { demo.store(); return demoMcpServers }
}

/** The demo account's setup from memory, every other account's from its server. */
class DemoAwareMemberSetupRepository(private val real: MemberSetupRepository, private val demo: MemberSetupRepository) : MemberSetupRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun environment(accountId: String) = of(accountId).environment(accountId)
    override suspend fun mcpServers(accountId: String) = of(accountId).mcpServers(accountId)
}
