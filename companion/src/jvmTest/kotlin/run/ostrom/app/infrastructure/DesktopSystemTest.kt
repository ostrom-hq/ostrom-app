// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import run.ostrom.app.BuildInfo
import run.ostrom.app.infrastructure.system.DesktopPlatformInfo
import run.ostrom.app.infrastructure.system.JavaSecureRandom
import run.ostrom.app.infrastructure.system.deviceModelOf
import run.ostrom.app.infrastructure.system.platformOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What the desktop reports about itself, and its random bytes. */
class DesktopSystemTest {
    @Test fun theOperatingSystemIsNamedFromTheJvmsOsName() {
        assertEquals("macOS", platformOf("Mac OS X"))
        assertEquals("macOS", platformOf("mac os x"))
        assertEquals("Windows", platformOf("Windows 11"))
        assertEquals("Linux", platformOf("Linux"))
        assertEquals("Linux", platformOf("FreeBSD"), "any other system is called Linux")
        assertEquals("Linux", platformOf(null))
    }

    @Test fun theDeviceModelIsTheHostNameOfTheEnvironmentOrTheSystemNamed() {
        assertEquals("PC-1", deviceModelOf("Windows", mapOf("COMPUTERNAME" to "PC-1", "HOSTNAME" to "h")::get))
        assertEquals("studio", deviceModelOf("Linux", mapOf("HOSTNAME" to "studio")::get))
        assertEquals("macOS desktop", deviceModelOf("macOS", emptyMap<String, String>()::get))
        assertEquals(DesktopPlatformInfo.deviceModel, DesktopPlatformInfo.deviceModel, "read once")
    }

    @Test fun theVersionIsTheBuildsAndTheSharedObjectAnswersWithTheRealSystem() {
        assertEquals(BuildInfo.VERSION, DesktopPlatformInfo.appVersion)
        assertTrue(DesktopPlatformInfo.platform in setOf("macOS", "Windows", "Linux"))
    }

    @Test fun secureRandomGivesTheRequestedNumberOfBytesAndDifferentOnesEachTime() {
        assertEquals(0, JavaSecureRandom.nextBytes(0).size)
        val first = JavaSecureRandom.nextBytes(32)
        val second = JavaSecureRandom.nextBytes(32)
        assertEquals(32, first.size)
        assertNotEquals(first.toList(), second.toList())
    }
}
