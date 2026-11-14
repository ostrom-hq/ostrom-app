// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.usecases.setup

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.MemberSetupRepository
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** The member's environment, names only; [AppException.Unsupported] from a server without `environment.v1`. */
class LoadMemberEnvironmentUseCase(
    private val repository: MemberSetupRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String): MemberEnvironment = guard(accountId) {
        if (!features.has(accountId, Features.ENVIRONMENT)) throw AppException.Unsupported(Features.ENVIRONMENT)
        repository.environment(accountId)
    }
}

/** The member's personal MCP servers; [AppException.Unsupported] from a server without `mcp.personal.v1`. */
class LoadPersonalMcpServersUseCase(
    private val repository: MemberSetupRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String): List<PersonalMcpServer> = guard(accountId) {
        if (!features.has(accountId, Features.MCP_PERSONAL)) throw AppException.Unsupported(Features.MCP_PERSONAL)
        repository.mcpServers(accountId)
    }
}
