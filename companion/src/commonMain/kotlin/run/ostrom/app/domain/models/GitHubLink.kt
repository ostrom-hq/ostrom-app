// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/GitHubLink.kt; see NOTICE.
package run.ostrom.app.domain.models

/** Where the member's GitHub link is (`GET /api/v1/github/link`). */
enum class GitHubLinkState {
    NOT_LINKED,
    LINKED,

    /** GitHub refused the stored tokens, or the instance's App changed: the member links again. */
    RECONNECT_REQUIRED,

    /** A state a newer server added: the link can be made again or removed, nothing more is claimed about it. */
    UNKNOWN,
}

/** The member's GitHub link. No token is ever read: only the account it names. */
data class GitHubLink(
    val state: GitHubLinkState,
    /** False until an administrator saved the instance's GitHub App: nothing can be linked. */
    val appConfigured: Boolean,
    val login: String? = null,
    /** When the member linked it, in epoch milliseconds; null when unreadable. */
    val linkedAt: Long? = null,
)

/** A device flow started on the server: open [verificationUri], enter [userCode], poll with [id] every [interval] seconds. */
data class GitHubDeviceLink(val id: String, val userCode: String, val verificationUri: String, val expiresAt: Long?, val interval: Int)

/** One answer of the server to the polling of a device flow. */
sealed interface GitHubPoll {
    /** Not authorized yet; [interval] is the new delay in seconds when GitHub asked to slow down. */
    data class Pending(val interval: Int?) : GitHubPoll
    data class Linked(val link: GitHubLink) : GitHubPoll
    data object Expired : GitHubPoll
    data object Denied : GitHubPoll
}

/** Why the server refused to start or follow a GitHub link, by its error key. */
enum class GitHubRefusal {
    /** `409 github_app_not_configured`: no GitHub App with a client id and secret on the instance. */
    APP_NOT_CONFIGURED,

    /** `409 github_device_flow_disabled`: the App forbids the device flow. */
    DEVICE_FLOW_DISABLED,

    /** `409 github_app_misconfigured`: GitHub refused the App's client, or issued another kind of token. */
    APP_MISCONFIGURED,

    /** `409 github_account_in_use`: that GitHub account is linked to another member. */
    ACCOUNT_IN_USE,

    /** `503 github_unreachable`: GitHub did not answer the server; trying again may pass. */
    UNREACHABLE,
}
