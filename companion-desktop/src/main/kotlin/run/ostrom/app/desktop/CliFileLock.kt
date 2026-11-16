// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Semaphore

/**
 * The exclusive lock the `ostrom` CLI takes on a file of its folder before it reads then writes it (ostrom-cli's
 * `FileStore.exclusive`): a `<file>.lock` beside it, created owner-only, never deleted. The app takes the very same
 * lock, so a CLI and the app never write one file at once: `flock` on macOS and Linux, `LockFileEx` on Windows.
 */
fun interface CliFileLocker {
    /**
     * Waits for, then takes, the exclusive lock of the lock file [lockFile] (created when missing); released when the
     * returned handle is closed.
     *
     * @throws IOException When the file cannot be created or locked.
     */
    fun lock(lockFile: Path): AutoCloseable

    companion object {
        /**
         * The lock the CLI takes on [os]. The JDK's file lock is `LockFileEx` on Windows, and on macOS `fcntl`, which
         * there excludes a `flock` holder; on Linux the two never see each other, so the app calls `flock` itself.
         */
        fun of(os: DesktopEnvironment.Os): CliFileLocker = if (os == DesktopEnvironment.Os.LINUX) FlockFileLocker else ChannelFileLocker
    }
}

/**
 * The JDK's lock of the whole file: `LockFileEx` on Windows, `fcntl` on macOS. The JDK's lock belongs to the whole JVM
 * (a second one throws instead of waiting), so the app's own holders wait for one another first.
 */
internal object ChannelFileLocker : CliFileLocker {
    private val inThisApp = Semaphore(1)

    override fun lock(lockFile: Path): AutoCloseable {
        inThisApp.acquire()
        try {
            createOwnerOnly(lockFile)
            val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                channel.lock()
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
}

/** The C library's `flock`, the CLI's own lock on Linux (its descriptor closed on `exec`, as the CLI's). */
internal object FlockFileLocker : CliFileLocker {
    // A failing call throws LastErrorException with its errno, read right after the call.
    private interface CLibrary : Library {
        @Throws(LastErrorException::class)
        fun open(path: String, flags: Int): Int

        @Throws(LastErrorException::class)
        fun flock(descriptor: Int, operation: Int): Int

        fun close(descriptor: Int): Int
    }

    private val c: CLibrary by lazy { Native.load("c", CLibrary::class.java) }

    // Linux's values, the same on x86_64 and aarch64.
    private const val O_RDWR = 0x2
    private const val O_CLOEXEC = 0x80000
    private const val LOCK_EX = 2
    private const val LOCK_UN = 8
    private const val EINTR = 4

    override fun lock(lockFile: Path): AutoCloseable {
        createOwnerOnly(lockFile)
        val library = try {
            c
        } catch (error: LinkageError) {
            // No C library JNA can load (its native part refused, an unusual system): no lock, said as a write failure.
            throw IOException("could not load the C library to lock $lockFile", error)
        }
        val descriptor = interrupted("open $lockFile") { library.open(lockFile.toString(), O_RDWR or O_CLOEXEC) }
        try {
            interrupted("lock $lockFile") { library.flock(descriptor, LOCK_EX) }
        } catch (error: IOException) {
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
