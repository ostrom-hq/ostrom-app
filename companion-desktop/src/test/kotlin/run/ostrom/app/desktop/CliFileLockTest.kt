// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The app's lock is the CLI's: a process that takes the file's `flock` (as `ostrom` does) waits while the app holds it. */
class CliFileLockTest {
    @Test
    fun eachSystemTakesTheCliLock() {
        assertIs<FlockFileLocker>(CliFileLocker.of(DesktopEnvironment.Os.LINUX))
        assertIs<ChannelFileLocker>(CliFileLocker.of(DesktopEnvironment.Os.MACOS))
        assertIs<ChannelFileLocker>(CliFileLocker.of(DesktopEnvironment.Os.WINDOWS))
    }

    @Test
    fun theAppWaitsForItsOwnHolderOnlyAsLongAsTheBound() = withTemporaryDirectory { directory ->
        val lockFile = File(directory, "ostrom/installation-id.lock").toPath()
        val os = DesktopEnvironment.Os.current()
        CliFileLocker.of(os).lock(lockFile).use {
            // Another sign-in of the app itself: on macOS and Windows the JVM's one holder, on Linux a second flock.
            val busy = assertFailsWith<CliLockBusyException> { CliFileLocker.of(os, timeoutMillis = 100).lock(lockFile) }
            assertEquals(lockFile, busy.lockFile)
        }
        CliFileLocker.of(os, timeoutMillis = 100).lock(lockFile).close()
    }

    // TeamCode's configuration lock waits 10 s, then refuses: a stuck CLI never blocks the sign-in screen forever.
    @Test
    fun aCliStuckWithTheLockMakesTheAppGiveUpAfterTheWait() = withTemporaryDirectory { directory ->
        val os = DesktopEnvironment.Os.current()
        if (os == DesktopEnvironment.Os.WINDOWS || !python3()) return@withTemporaryDirectory
        val lockFile = File(directory, "ostrom/installation-id.lock")
        createOwnerOnly(lockFile.toPath())
        val holder = holdFlock(lockFile)
        try {
            val started = System.nanoTime()
            assertFailsWith<CliLockBusyException> { CliFileLocker.of(os, timeoutMillis = 300).lock(lockFile.toPath()) }
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(waited in 300..5_000, "waited $waited ms")
        } finally {
            holder.destroy()
            holder.waitFor(30, TimeUnit.SECONDS)
        }
        // The holder gone, the lock is the app's again.
        CliFileLocker.of(os, timeoutMillis = 5_000).lock(lockFile.toPath()).close()
    }

    /** A process that takes the file's `flock`, as `ostrom` does, says so and keeps it until it is stopped. */
    private fun holdFlock(file: File): Process {
        val script = "import fcntl, os, sys, time\n" +
            "fd = os.open(sys.argv[1], os.O_RDWR)\n" +
            "fcntl.flock(fd, fcntl.LOCK_EX); print('held', flush=True)\n" +
            "time.sleep(60)\n"
        val process = ProcessBuilder("python3", "-I", "-c", script, file.path).redirectErrorStream(true).start()
        assertEquals("held", process.inputStream.bufferedReader().readLine())
        return process
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
