// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The exclusive lock the `ostrom` CLI takes on a file of its folder before it reads then writes it (ostrom-cli's
 * `FileStore.exclusive`): a `<file>.lock` beside it, created owner-only, never deleted. The app takes the very same
 * lock, so a CLI and the app never write one file at once: `flock` on macOS and Linux, `LockFileEx` on Windows.
 *
 * The wait is bounded, as TeamCode's configuration lock (`withFileLock`, 10 s): a CLI stuck while it holds the lock
 * makes the app refuse with [CliLockBusyException] instead of waiting forever.
 */
fun interface CliFileLocker {
    /**
     * Waits for, then takes, the exclusive lock of the lock file [lockFile] (created when missing); released when the
     * returned handle is closed.
     *
     * @throws CliLockBusyException When another process still holds it after the locker's wait.
     * @throws IOException When the file cannot be created or locked.
     */
    fun lock(lockFile: Path): AutoCloseable

    companion object {
        /** How long a lock is waited for: TeamCode's `DEFAULT_LOCK_TIMEOUT_MILLIS`. */
        const val TIMEOUT_MILLIS: Long = 10_000

        /**
         * The lock the CLI takes on [os], waited for at most [timeoutMillis]. The JDK's file lock is `LockFileEx` on
         * Windows, and on macOS `fcntl`, which there excludes a `flock` holder; on Linux the two never see each other,
         * so the app calls `flock` itself.
         */
        fun of(os: DesktopEnvironment.Os, timeoutMillis: Long = TIMEOUT_MILLIS): CliFileLocker =
            if (os == DesktopEnvironment.Os.LINUX) FlockFileLocker(timeoutMillis) else ChannelFileLocker(timeoutMillis)
    }
}

/** Another process (a CLI command, maybe stuck) still holds [lockFile] after the wait: the app does not wait more. */
class CliLockBusyException(val lockFile: Path) : IOException("$lockFile is held by another process")

/** The pause between two attempts to take a busy lock, as TeamCode's. */
private const val RETRY_MILLIS = 25L

/** [attempt] again every [RETRY_MILLIS] until it takes the lock (non-null), or [CliLockBusyException] past [deadline]. */
private inline fun <T : Any> untilDeadline(lockFile: Path, deadline: Long, attempt: () -> T?): T {
    while (true) {
        attempt()?.let { return it }
        if (System.nanoTime() - deadline >= 0) throw CliLockBusyException(lockFile)
        Thread.sleep(RETRY_MILLIS)
    }
}

private fun deadlineIn(timeoutMillis: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

/**
 * The JDK's lock of the whole file: `LockFileEx` on Windows, `fcntl` on macOS. The JDK's lock belongs to the whole JVM
 * (a second one throws instead of waiting), so the app's own holders wait for one another first, within the same bound.
 */
internal class ChannelFileLocker(private val timeoutMillis: Long) : CliFileLocker {
    override fun lock(lockFile: Path): AutoCloseable {
        val deadline = deadlineIn(timeoutMillis)
        if (!inThisApp.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) throw CliLockBusyException(lockFile)
        try {
            createOwnerOnly(lockFile)
            val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                untilDeadline<FileLock>(lockFile, deadline) {
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                }
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
            // Closing the channel releases its lock.
            return AutoCloseable {
                try {
                    channel.close()
                } finally {
                    inThisApp.release()
                }
            }
        } catch (error: Throwable) {
            inThisApp.release()
            throw error
        }
    }

    private companion object {
        /** One holder in the whole app, whatever the locker instance: the JDK's lock is the JVM's. */
        val inThisApp = Semaphore(1)
    }
}

/** The C library's `flock`, the CLI's own lock on Linux (its descriptor closed on `exec`, as the CLI's). */
internal class FlockFileLocker(private val timeoutMillis: Long) : CliFileLocker {
    // A failing call throws LastErrorException with its errno, read right after the call.
    private interface CLibrary : Library {
        @Throws(LastErrorException::class)
        fun open(path: String, flags: Int): Int

        @Throws(LastErrorException::class)
        fun flock(descriptor: Int, operation: Int): Int

        fun close(descriptor: Int): Int
    }

    override fun lock(lockFile: Path): AutoCloseable {
        val deadline = deadlineIn(timeoutMillis)
        createOwnerOnly(lockFile)
        val library = try {
            c
        } catch (error: LinkageError) {
            // No C library JNA can load (its native part refused, an unusual system): no lock, said as a write failure.
            throw IOException("could not load the C library to lock $lockFile", error)
        }
        val descriptor = interrupted("open $lockFile") { library.open(lockFile.toString(), O_RDWR or O_CLOEXEC) }
        try {
            untilDeadline(lockFile, deadline) { tryFlock(library, descriptor, lockFile) }
        } catch (error: Throwable) {
            // Busy, failed, or interrupted between two attempts: the descriptor never outlives the call.
            library.close(descriptor)
            throw error
        }
        return AutoCloseable {
            try {
                library.flock(descriptor, LOCK_UN)
            } catch (_: LastErrorException) {
                // Closing the descriptor releases the lock anyway.
            }
            library.close(descriptor)
        }
    }

    /** Takes the lock without waiting: null while another process holds it. */
    private fun tryFlock(library: CLibrary, descriptor: Int, lockFile: Path): Int? = try {
        interrupted("lock $lockFile") { library.flock(descriptor, LOCK_EX or LOCK_NB) }
    } catch (error: IOException) {
        if ((error.cause as? LastErrorException)?.errorCode == EWOULDBLOCK) null else throw error
    }

    /** [call] again while a signal interrupted it; any other failure is an [IOException] naming [what] and its errno. */
    private inline fun interrupted(what: String, call: () -> Int): Int {
        while (true) {
            try {
                return call()
            } catch (error: LastErrorException) {
                if (error.errorCode != EINTR) throw IOException("could not $what (errno ${error.errorCode})", error)
            }
        }
    }

    private companion object {
        val c: CLibrary by lazy { Native.load("c", CLibrary::class.java) }

        // Linux's values, the same on x86_64 and aarch64.
        const val O_RDWR = 0x2
        const val O_CLOEXEC = 0x80000
        const val LOCK_EX = 2
        const val LOCK_NB = 4
        const val LOCK_UN = 8
        const val EINTR = 4
        const val EWOULDBLOCK = 11
    }
}

/**
 * Creates [file] readable and writable by its owner only (0600) when missing, as the CLI creates its files; a no-op on
 * Windows, whose profile is private (the CLI's `restrictToOwner`).
 */
internal fun createOwnerOnly(file: Path) {
    Files.createDirectories(file.toAbsolutePath().parent)
    if (!file.fileSystem.supportedFileAttributeViews().contains("posix")) return
    try {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } catch (_: FileAlreadyExistsException) {
        // Created by another run, or by the CLI: kept as it is.
    }
}
