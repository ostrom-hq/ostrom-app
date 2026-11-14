// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/NotificationPreferences.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * What a member can be notified about (`notifications.v1`, ostrom-core's `NotificationKinds`, held equal by a test), in
 * the order the screen lists them.
 */
enum class NotificationKind(val wire: String) {
    WAITING("waiting"),
    TURN_COMPLETED("turn_completed"),
    TURN_FAILED("turn_failed"),
    SESSION_FAILED("session_failed");

    companion object {
        /** The kind of a wire name; null for one this app does not know, from a newer server. */
        fun parse(wire: String): NotificationKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** The channels a server may offer; a channel this app does not name is listed under its wire name. */
object NotificationChannels {
    const val PUSH = "push"
}

/** One switch: one [kind] on one [channel]. */
data class NotificationSwitch(val channel: String, val kind: NotificationKind)

/**
 * The member's switches on one server (`/api/v1/notifications/settings`): the [channels] it offers, in its order, and
 * every switch in force. They are the member's, kept by the server, so they hold on all their phones. A switch the
 * server does not list is on.
 */
data class NotificationSettings(val channels: List<String>, val switches: Map<NotificationSwitch, Boolean>) {
    fun isOn(channel: String, kind: NotificationKind): Boolean = switches[NotificationSwitch(channel, kind)] ?: true

    fun with(channel: String, kind: NotificationKind, on: Boolean): NotificationSettings =
        copy(switches = switches + (NotificationSwitch(channel, kind) to on))
}

/** The switches, and whether this server lets the member send a test push (`notifications.push.test.v1`). */
data class NotificationSettingsView(val settings: NotificationSettings, val canTest: Boolean)

/** How one phone took a test push. */
enum class PushTestStatus(val wire: String) {
    /** The provider accepted it. */
    SENT("sent"),

    /** The provider no longer knows this phone: the app on it registers it again at its next start. */
    UNREGISTERED("unregistered"),

    /** The provider asked to try later. */
    RETRY("retry"),

    /** Refused for good. */
    REJECTED("rejected"),

    /** A status this app does not know, from a newer server. */
    OTHER("");

    companion object {
        fun parse(wire: String): PushTestStatus = entries.firstOrNull { it.wire == wire && it != OTHER } ?: OTHER
    }
}

/** One phone's result: its [status] and the server's short [error] code, if any. */
data class PushTestPhone(val status: PushTestStatus, val error: String? = null)

/** How a test push went. */
sealed interface PushTestOutcome {
    /** Sent to each of the member's [phones]; [delivered] of them accepted it. */
    data class Sent(val phones: List<PushTestPhone>) : PushTestOutcome {
        val delivered: Int get() = phones.count { it.status == PushTestStatus.SENT }
    }

    /** The server has no push provider (`409 push_off`): an administrator chooses one in the dashboard. */
    data object Off : PushTestOutcome

    /** No phone of the member is registered (`409 push_no_device`): the app on a phone, signed in, registers it. */
    data object NoPhone : PushTestOutcome
}
