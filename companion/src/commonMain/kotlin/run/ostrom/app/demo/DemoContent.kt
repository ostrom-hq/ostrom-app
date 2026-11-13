// SPDX-License-Identifier: AGPL-3.0-only
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's content; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SignInMethod

/** The built-in demo's account and content, in English. Each feature adds its sample data here. */
object DemoContent {
    const val ACCOUNT_ID = "demo"

    /** A domain that never resolves (RFC 6761): nothing of the demo can reach a network. */
    const val ORIGIN = "https://demo.ostrom.invalid"
    const val SERVER_VERSION = "demo"
    const val REPOSITORY_URL = "https://github.com/ostrom-hq/ostrom-server"

    /** What the demo server announces: the features whose demo content exists. */
    val features: Set<String> = setOf(Features.WAITS)

    fun account(): Account = Account(
        id = ACCOUNT_ID,
        origin = ORIGIN,
        login = "ada",
        name = "Ada Lovelace",
        organizationId = "org-demo",
        organizationName = "Analytical Engines",
        serverVersion = SERVER_VERSION,
        method = SignInMethod.BROWSER,
    )
}

/** Whether this account is the built-in demo's. */
val Account.isDemo: Boolean get() = id == DemoContent.ACCOUNT_ID

internal fun isDemo(accountId: String): Boolean = accountId == DemoContent.ACCOUNT_ID
