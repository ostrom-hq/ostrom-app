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

    /** The waits asked of the person ("Waiting for you"). */
    const val WAITS = "waits.v1"

    /** Shared artifacts and their viewer. */
    const val ARTIFACTS = "artifacts.v1"
}

/** Throws [AppException.UnsupportedApi] for a server that speaks another API version than this app. */
fun requireSupportedApi(apiVersion: Int) {
    if (apiVersion != Features.SUPPORTED_API_VERSION) throw AppException.UnsupportedApi(apiVersion)
}
