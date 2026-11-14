// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.models

/**
 * The API version this app speaks and the feature keys it has a screen for: the values of ostrom-core's
 * `ServerFeatures` (a test holds them equal). A server that speaks another API version is refused; a feature it does
 * not announce hides its screen.
 */
object Features {
    const val SUPPORTED_API_VERSION = 1

    /** Gates, approvals and checks: the approvals inbox. */
    const val GATES = "gates.v1"

    /** Signed receipts: the history. */
    const val RECEIPTS = "receipts.v1"

    /** Spend budgets: the spending screen. */
    const val BUDGETS = "budgets.v1"

    /** Notification preferences. */
    const val NOTIFICATIONS = "notifications.v1"

    /** Push to this phone. */
    const val NOTIFICATIONS_PUSH = "notifications.push.v1"

    /** Any member may send a test push to their own phones: the "Send a test" button of Notifications. */
    const val NOTIFICATIONS_PUSH_TEST = "notifications.push.test.v1"

    /** The waits asked of the person ("Waiting for you"). */
    const val WAITS = "waits.v1"

    /** The organization's live event feed (`GET /api/v1/events`): the waits, the sessions, the todos and the runs follow it. */
    const val EVENTS = "events.v1"

    /** Shared artifacts and their viewer. */
    const val ARTIFACTS = "artifacts.v1"

    /** The organization's agent sessions: the Sessions tab and a session's record. */
    const val SESSIONS = "sessions.v1"

    /** A session's todo: the panel of a session's record. */
    const val TODOS = "todos.v1"

    /** Workflows and their runs, read only: the Workflows tab. */
    const val WORKFLOWS = "workflows.v1"

    /** The member's environment variables, names only, and their personal variants: Settings > Environment. */
    const val ENVIRONMENT = "environment.v1"

    /** The member's personal MCP servers: Settings > MCP servers. */
    const val MCP_PERSONAL = "mcp.personal.v1"

    /** The person's cloud sessions: listed, read, started, paused, resumed and stopped from the Sessions tab. */
    const val RUNNERS_CLOUD = "runners.cloud.v1"

    /** A cloud session's preparation recipe: the new cloud session form takes repositories, setup and variables. */
    const val RUNNERS_CLOUD_ENVIRONMENT = "runners.cloud.environment.v1"

    /** The member's GitHub link: Settings > GitHub. */
    const val GITHUB_LINK = "github.link.v1"

    /** A cloud session's GitHub access (`owner`, `shared`, `none`): the choice on its record. */
    const val RUNNERS_CLOUD_GITHUB = "runners.cloud.github.v1"
}

/** Throws [AppException.UnsupportedApi] for a server that speaks another API version than this app. */
fun requireSupportedApi(apiVersion: Int) {
    if (apiVersion != Features.SUPPORTED_API_VERSION) throw AppException.UnsupportedApi(apiVersion)
}
