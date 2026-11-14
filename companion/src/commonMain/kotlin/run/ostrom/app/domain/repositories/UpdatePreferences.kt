// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/UpdatePreferences.kt; see NOTICE.
package run.ostrom.app.domain.repositories

/** What the update banner remembers across restarts. */
interface UpdatePreferences {
    /** The version whose banner the person closed: it does not show again for that version. */
    var dismissedVersion: String?

    /** When the last check was attempted, successful or not (epoch milliseconds). */
    var lastAttemptMillis: Long?

    /** The latest release the last successful check found; null when never checked. */
    var latestVersion: String?
}
