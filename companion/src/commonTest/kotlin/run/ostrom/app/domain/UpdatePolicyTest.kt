// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../UpdatePolicyTest.kt; see NOTICE.
package run.ostrom.app.domain

import run.ostrom.app.domain.usecases.updates.UpdatePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatePolicyTest {
    @Test
    fun aNewerReleaseIsOffered() {
        assertEquals("1.3.0", UpdatePolicy.target("1.2.9", "1.3.0"))
        assertEquals("1.10.0", UpdatePolicy.target("1.9.0", "1.10.0"), "Compared as numbers, not text")
        assertEquals("2.0.0", UpdatePolicy.target("2.0.0-rc.2", "2.0.0"), "A release is newer than its prerelease")
        assertEquals("2.0.0-rc.10", UpdatePolicy.target("2.0.0-rc.9", "2.0.0-rc.10"))
        assertEquals("2.0.0-rc.1", UpdatePolicy.target("2.0.0-beta", "2.0.0-rc.1"))
        assertEquals("2.0.0-rc.1.1", UpdatePolicy.target("2.0.0-rc.1", "2.0.0-rc.1.1"))
        assertEquals("2.0.0-rc", UpdatePolicy.target("2.0.0-1", "2.0.0-rc"), "A word comes after a number")
    }

    @Test
    fun nothingIsOfferedWhenTheCliIsCurrentMissingOrUnreadable() {
        assertNull(UpdatePolicy.target("1.3.0", "1.3.0"))
        assertNull(UpdatePolicy.target("1.4.0", "1.3.0"), "Updated in a terminal since the last check")
        assertNull(UpdatePolicy.target(null, "1.3.0"))
        assertNull(UpdatePolicy.target("1.3.0", null))
        assertNull(UpdatePolicy.target("1.3", "1.4.0"))
        assertNull(UpdatePolicy.target("1.3.0", "v1.4.0"))
        assertNull(UpdatePolicy.target("2.0.0", "2.0.0-rc.1"))
        assertNull(UpdatePolicy.target("2.0.0-rc.2", "2.0.0-rc.1"))
        assertNull(UpdatePolicy.target("2.0.0-rc", "2.0.0-1"))
    }

    @Test
    fun versionsAreReadStrictly() {
        assertTrue(UpdatePolicy.isVersion("0.4.1"))
        assertTrue(UpdatePolicy.isVersion("1.2.3-rc.1"))
        assertFalse(UpdatePolicy.isVersion("01.2.3"))
        assertFalse(UpdatePolicy.isVersion("1.2.3; rm -rf /"))
        assertFalse(UpdatePolicy.isVersion("1.2.3-" + "a".repeat(80)))
    }

    @Test
    fun checksAreSpacedAcrossRestarts() {
        assertEquals(0, UpdatePolicy.nextCheckDelay(1_000, null))
        assertEquals(UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS - 1_000, UpdatePolicy.nextCheckDelay(2_000, 1_000))
        assertEquals(0, UpdatePolicy.nextCheckDelay(1_000 + UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS, 1_000))
        assertEquals(0, UpdatePolicy.nextCheckDelay(500, 1_000), "A clock that went back checks at once")
    }
}
