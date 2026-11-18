// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import run.ostrom.app.BuildInfo
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.infrastructure.system.DesktopPlatformInfo
import run.ostrom.app.infrastructure.system.JavaSecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What the desktop reports about itself, and its random bytes. */
class DesktopSystemTest {
    /** Loads the classes of [DesktopPlatformInfo] anew, so that its initializer reads the JVM's current `os.name`. */
    private class Isolated(parent: ClassLoader) : ClassLoader(parent) {
        private val prefix = DesktopPlatformInfo::class.java.name

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith(prefix)) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.readBytes()
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
    }

    /** A [DesktopPlatformInfo] as if the JVM reported [osName], since the object reads it once. */
    private fun platformOn(osName: String): PlatformInfo {
        val previous = System.getProperty("os.name")
        System.setProperty("os.name", osName)
        try {
            val loaded = Class.forName(DesktopPlatformInfo::class.java.name, true, Isolated(DesktopPlatformInfo::class.java.classLoader))
            return loaded.getField("INSTANCE").get(null) as PlatformInfo
        } finally {
            System.setProperty("os.name", previous)
        }
    }

    @Test fun theOperatingSystemIsNamedFromTheJvmsOsName() {
        assertEquals("macOS", platformOn("Mac OS X").platform)
        assertEquals("macOS", platformOn("mac os x").platform)
        assertEquals("Windows", platformOn("Windows 11").platform)
        assertEquals("Linux", platformOn("Linux").platform)
        assertEquals("Linux", platformOn("FreeBSD").platform, "any other system is called Linux")
    }

    @Test fun theDeviceModelIsTheHostNameOfTheEnvironmentOrTheSystemNamed() {
        val info = platformOn("Mac OS X")
        val expected = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "macOS desktop"
        assertEquals(expected, info.deviceModel)
        assertEquals(expected, info.deviceModel, "read once")
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
