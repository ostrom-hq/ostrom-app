// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.MemberEnvironment
import run.ostrom.app.domain.models.PersonalMcpServer

/**
 * What the member set up for their own agents on an account's server, read only: changes are made in the console
 * (they need a browser sign-in of less than five minutes). Failures are [run.ostrom.app.domain.models.AppException]s.
 */
interface MemberSetupRepository {
    /** The variable names the member receives, those withheld, and their personal variants. */
    suspend fun environment(accountId: String): MemberEnvironment

    /** The member's personal MCP servers. */
    suspend fun mcpServers(accountId: String): List<PersonalMcpServer>
}
