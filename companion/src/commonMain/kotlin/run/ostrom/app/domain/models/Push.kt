// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion domain/models/PushMessage.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.models

/**
 * A push once decrypted (ostrom-core's `PushPayload`): which server, organization and member it is for, its text, and
 * what a tap opens.
 *
 * @property kind `approval`, a notification kind (`waiting`, `turn_completed`…) or `test`.
 * @property approvalId The request waiting for the member's yes, for `approval`.
 * @property sessionId The session it is about.
 * @property waitId The wait it is about, for a wait outside a session.
 */
data class PushMessage(
    val id: String,
    val origin: String,
    val orgId: String,
    val memberId: String,
    val kind: String,
    val title: String,
    val body: String,
    val approvalId: String? = null,
    val sessionId: String? = null,
    val waitId: String? = null,
)

/** A push to show: its text, and the link a tap opens ([run.ostrom.app.domain.usecases.push.NotificationLinks]). */
data class PushNotice(val id: String, val accountId: String, val title: String, val body: String, val link: String)
