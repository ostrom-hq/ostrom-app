// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion domain/models/NotificationPreferences.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.models

/** Hours without notifications: [start] and [end] as `HH:mm` in [timeZone], an IANA zone id. */
data class QuietHours(val start: String, val end: String, val timeZone: String)

/** What the person is notified of on their phones, in the order the screen lists them. */
enum class NotificationToggle { REQUESTS, DECISIONS, SPENDING }

/**
 * What the person is notified of (`/api/v1/me/notifications`): the server keeps it for them, so it holds on all their
 * phones whichever device changed it. No [quietHours]: notified at any hour.
 */
data class NotificationPreferences(
    val requests: Boolean,
    val decisions: Boolean,
    val spending: Boolean,
    val quietHours: QuietHours? = null,
) {
    operator fun get(toggle: NotificationToggle): Boolean = when (toggle) {
        NotificationToggle.REQUESTS -> requests
        NotificationToggle.DECISIONS -> decisions
        NotificationToggle.SPENDING -> spending
    }

    fun with(toggle: NotificationToggle, on: Boolean): NotificationPreferences = when (toggle) {
        NotificationToggle.REQUESTS -> copy(requests = on)
        NotificationToggle.DECISIONS -> copy(decisions = on)
        NotificationToggle.SPENDING -> copy(spending = on)
    }

    /** Quiet hours turned on (from [DEFAULT_QUIET_START] to [DEFAULT_QUIET_END] in the phone's [timeZone]) or off. */
    fun withQuietHours(on: Boolean, timeZone: String): NotificationPreferences =
        copy(quietHours = if (on) quietHours ?: QuietHours(DEFAULT_QUIET_START, DEFAULT_QUIET_END, timeZone) else null)

    companion object {
        const val DEFAULT_QUIET_START = "22:00"
        const val DEFAULT_QUIET_END = "07:00"
    }
}
