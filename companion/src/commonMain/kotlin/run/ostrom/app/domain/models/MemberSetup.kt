// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.models

/**
 * The member's environment (`environment.v1`), as names only: the app never receives nor shows a value.
 *
 * @property names The variables the member's agents receive now, sorted.
 * @property unavailable Assigned variables withheld because their value cannot be read now, sorted.
 * @property variants The member's own personal variants, by variable then name.
 */
data class MemberEnvironment(val names: List<String>, val unavailable: List<String>, val variants: List<PersonalVariant>)

/**
 * One of the member's personal environment variants: a value of theirs for [variable], written once in the console and
 * never read back. [hasValue] says a value is stored; [updatedAt] is in epoch milliseconds, null when unreadable.
 */
data class PersonalVariant(val id: String, val variable: String, val name: String, val hasValue: Boolean, val updatedAt: Long?)

/** How an MCP server is reached: a remote HTTPS server, or a command the server's MCP host runs. */
enum class McpTransport {
    HTTP, STDIO, UNKNOWN;

    companion object {
        fun parse(value: String): McpTransport = when (value) {
            "http" -> HTTP
            "stdio" -> STDIO
            else -> UNKNOWN
        }
    }
}

/**
 * A hosted command's process, as the server's sweep last recorded it. [Untracked] for an HTTP server or when the state
 * cannot be read; [Failed] carries the host's failure code, shown only when it is a stable key.
 */
sealed interface McpProcess {
    data object Untracked : McpProcess
    data object Idle : McpProcess
    data object Starting : McpProcess
    data object Ready : McpProcess
    data object Backoff : McpProcess
    data class Failed(val code: String?) : McpProcess

    companion object {
        fun parse(state: String?, failure: String?): McpProcess = when (state) {
            "idle" -> Idle
            "starting" -> Starting
            "ready" -> Ready
            "backoff" -> Backoff
            "failed" -> Failed(visibleCode(failure))
            else -> Untracked
        }
    }
}

/**
 * One of the member's personal MCP servers (`mcp.personal.v1`), relayed to them alone. A hosted command shows its
 * [launcher] and pinned [packageName], which never change after creation; a remote one its [url]. [secretNames] are
 * the names of the member's secrets it uses, never their values.
 */
data class PersonalMcpServer(
    val name: String,
    val agentName: String?,
    val transport: McpTransport,
    val url: String?,
    val launcher: String?,
    val packageName: String?,
    val secretNames: List<String>,
    val enabled: Boolean,
    val process: McpProcess,
)
