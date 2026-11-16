// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The app's lock is the CLI's: a process that takes the file's `flock` (as `ostrom` does) waits while the app holds it. */
class CliFileLockTest {
    @Test
    fun eachSystemTakesTheCliLock() {
        assertSame(FlockFileLocker, CliFileLocker.of(DesktopEnvironment.Os.LINUX))
        assertSame(ChannelFileLocker, CliFileLocker.of(DesktopEnvironment.Os.MACOS))
        assertSame(ChannelFileLocker, CliFileLocker.of(DesktopEnvironment.Os.WINDOWS))
    }

    @Test
    fun aFlockHolderIsExcludedWhileTheAppHoldsTheLock() = withTemporaryDirectory { directory ->
        val os = DesktopEnvironment.Os.current()
        // flock is the CLI's lock on macOS and Linux; python3 takes it the same way (on Windows the CLI's LockFileEx is the JDK's own).
        if (os == DesktopEnvironment.Os.WINDOWS || !python3()) return@withTemporaryDirectory
        val lockFile = File(directory, "ostrom/installation-id.lock")
        CliFileLocker.of(os).lock(lockFile.toPath()).use {
            assertEquals("busy", tryFlock(lockFile))
        }
        assertEquals("taken", tryFlock(lockFile))
    }

    private fun python3(): Boolean = try {
        ProcessBuilder("python3", "-I", "-c", "pass").start().waitFor(30, TimeUnit.SECONDS)
    } catch (_: java.io.IOException) {
        false
    }

    private fun tryFlock(file: File): String {
        val script = "import fcntl, os, sys\n" +
            "fd = os.open(sys.argv[1], os.O_RDWR)\n" +
            "try:\n    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB); print('taken')\n" +
            "except BlockingIOError:\n    print('busy')\n"
        val process = ProcessBuilder("python3", "-I", "-c", script, file.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(30, TimeUnit.SECONDS)
        return output
    }
}
