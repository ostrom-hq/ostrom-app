// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure.repositories

import io.ktor.http.URLParserException
import io.ktor.http.Url
import run.ostrom.app.domain.models.McpProcess
import run.ostrom.app.domain.models.McpTransport
import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.domain.models.PersonalVariant
import run.ostrom.app.domain.repositories.MemberSetupRepository
import run.ostrom.core.api.models.McpServer

/**
 * The member's environment (`GET /api/v1/environment`, names only: the typed client drops the values, and
 * `GET /api/v1/me/environment/variants`) and personal MCP servers (`GET /api/v1/me/mcp/servers`) over the typed client.
 */
class RemoteMemberSetupRepository(private val calls: AccountCalls) : MemberSetupRepository {
    override suspend fun environment(accountId: String): MemberEnvironment = calls.withAccount(accountId) { client ->
        val names = client.environment.names()
        val variants = client.environment.listMine().items.map { variant ->
            PersonalVariant(variant.id, variant.variable, variant.name, variant.hasValue, RemoteAuthRepository.epochMillis(variant.updatedAt))
        }
        MemberEnvironment(names.names, names.unavailable, variants)
    }

    override suspend fun mcpServers(accountId: String): List<PersonalMcpServer> =
        calls.withAccount(accountId) { client -> client.myMcpServers.list().items.map { it.domain() }.sortedBy { it.name } }

    private fun McpServer.domain(): PersonalMcpServer {
        val kind = McpTransport.parse(transport)
        return PersonalMcpServer(
            name = name,
            agentName = agentName,
            transport = kind,
            // A stdio server's url reads `stdio:<launcher>:<package>`: its launcher and package say it better.
            url = url.takeIf { kind == McpTransport.HTTP }?.let(::shown),
            launcher = stdio?.launcher,
            packageName = stdio?.packageName,
            secretNames = (secretNames.orEmpty() + stdio?.secretEnv?.values.orEmpty()).distinct().sorted(),
            enabled = enabled,
            process = if (kind == McpTransport.STDIO) McpProcess.parse(state, failure) else McpProcess.Untracked,
        )
    }

    /** An address as shown: scheme, host, port and path only, so a credential in its user part or query never shows. */
    private fun shown(address: String): String = try {
        val url = Url(address)
        val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
        "${url.protocol.name}://${url.host}$port${url.encodedPath}"
    } catch (_: URLParserException) {
        address.substringBefore('?').substringBefore('#').substringAfterLast('@')
    }
}
