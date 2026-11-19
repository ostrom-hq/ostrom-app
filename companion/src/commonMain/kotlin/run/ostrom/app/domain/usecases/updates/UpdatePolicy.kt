// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/updates/UpdatePolicy.kt; see NOTICE.
package run.ostrom.app.domain.usecases.updates

/** When the update banner shows, and for which version of the CLI. */
object UpdatePolicy {
    /** At most one check in this long, across restarts: each one asks GitHub for the release manifest. */
    const val MIN_CHECK_INTERVAL_MILLIS = 60 * 60_000L

    /** While the app runs, it checks again this often. */
    const val CHECK_INTERVAL_MILLIS = 6 * 60 * 60_000L

    /**
     * The version an update would bring, or null when there is nothing to offer: no CLI, a version that does not read
     * as one, or a CLI already on [latestVersion] or newer (updated in a terminal since the last check).
     */
    fun target(cliVersion: String?, latestVersion: String?): String? {
        val cli = cliVersion?.let(::parse) ?: return null
        val latest = latestVersion?.let(::parse) ?: return null
        return latest.value.takeIf { latest > cli }
    }

    /** How long to wait before the next check, given the last attempt; 0 when it is due (or the clock went back). */
    fun nextCheckDelay(nowMillis: Long, lastAttemptMillis: Long?, interval: Long = MIN_CHECK_INTERVAL_MILLIS): Long {
        val last = lastAttemptMillis ?: return 0
        val elapsed = nowMillis - last
        return if (elapsed < 0 || elapsed >= interval) 0 else interval - elapsed
    }

    /** Whether [value] reads as a release version (`1.2.3`, `1.2.3-rc.1`). */
    fun isVersion(value: String): Boolean = parse(value) != null

    private fun parse(value: String): Version? = if (value.length <= MAX_LENGTH && pattern.matches(value)) Version(value) else null

    private const val MAX_LENGTH = 80

    private val pattern = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")

    /** SemVer ordering, prereleases included; the versions come out of the CLI, which already verified them. */
    private class Version(val value: String) : Comparable<Version> {
        private val core = value.substringBefore('-').split('.').map { it.toLong() }
        private val prerelease = value.substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.')

        override fun compareTo(other: Version): Int {
            for (index in core.indices) core[index].compareTo(other.core[index]).let { if (it != 0) return it }
            val left = prerelease ?: return if (other.prerelease == null) 0 else 1
            val right = other.prerelease ?: return -1
            for (index in 0 until minOf(left.size, right.size)) {
                val a = left[index].toLongOrNull()
                val b = right[index].toLongOrNull()
                val comparison = when {
                    a != null && b != null -> a.compareTo(b)
                    a != null -> -1
                    b != null -> 1
                    else -> left[index].compareTo(right[index])
                }
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }
    }
}
