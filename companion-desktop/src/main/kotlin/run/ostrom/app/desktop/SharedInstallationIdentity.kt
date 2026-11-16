// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../SharedInstallationIdentity.kt and config/.../InstallationIdStore.kt; see NOTICE.
package run.ostrom.app.desktop

import com.russhwolf.settings.Settings
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.services.InstallationIdentity
import run.ostrom.app.infrastructure.settings.INSTALLATION_ID
import run.ostrom.app.infrastructure.settings.SettingsInstallationIdentity

/**
 * The CLI's `installation-id`, in its folder beside `config.toml` (ostrom-cli's `InstallationIdStore`): the app and the
 * CLI of one computer are one device on the server.
 *
 * The id is created as the CLI creates it, under the same lock (`installation-id.lock`, [CliFileLocker]), and read
 * again there first, so a CLI that wrote one meanwhile wins; it is published complete and without replacement, so the
 * app never overwrites one. An app that drew its own id before (in its preferences) publishes that one when the CLI has
 * none yet, so its device keeps its name and sessions. A read that fails while another first run publishes the file is
 * tried again, 20 times 25 ms apart (TeamCode #281). A file that holds anything but an id, or that cannot be read or
 * written, is [AppException.InstallationUnreadable] and stays as it is. The lock is waited for 10 s at most (as
 * TeamCode's configuration lock): a CLI stuck while it holds it is [AppException.InstallationBusy], never an endless wait.
 *
 * @param directory The CLI's folder ([DesktopEnvironment.cliDirectory]).
 * @param sleep Waits the given milliseconds between two reads (virtual in tests).
 */
class SharedInstallationIdentity(
    directory: String,
    private val settings: Settings,
    private val ids: IdGenerator,
    private val os: DesktopEnvironment.Os,
    private val locker: CliFileLocker = CliFileLocker.of(os),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : InstallationIdentity {
    private val path: Path = Path.of(directory, FILE)
    private val lockFile: Path = Path.of(directory, "$FILE.lock")

    override suspend fun id(): String = withContext(Dispatchers.IO) { read() ?: create() }

    private suspend fun create(): String {
        val created = try {
            locker.lock(lockFile).use {
                if (exists()) null else (legacy() ?: ids.newId()).takeIf(::publish)
            }
        } catch (_: CliLockBusyException) {
            throw AppException.InstallationBusy()
        } catch (_: IOException) {
            // Neither the lock nor the file could be written: said as such, never retried over another's file.
            throw AppException.InstallationUnreadable()
        } catch (_: UnsupportedOperationException) {
            // A file system without hard links (FAT): the id cannot be published without replacement there.
            throw AppException.InstallationUnreadable()
        }
        return created ?: read() ?: throw AppException.InstallationUnreadable()
    }

    /** The id, or null when there is no file yet. */
    private suspend fun read(): String? {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        } catch (_: IOException) {
            throw AppException.InstallationUnreadable()
        }
        // A link, a folder or a large file is not one the CLI wrote.
        if (!attributes.isRegularFile || attributes.size() > MAX_BYTES) throw AppException.InstallationUnreadable()
        val id = readRetrying()
        if (!INSTALLATION_ID.matches(id)) throw AppException.InstallationUnreadable()
        return id
    }

    /**
     * Windows can refuse to open a file for a moment while another first run publishes it (a sharing violation reads
     * as "not found" although it exists). The file is complete once published, so a short, bounded retry reads it; a
     * real failure still surfaces after the last attempt.
     */
    private suspend fun readRetrying(): String {
        repeat(READ_ATTEMPTS - 1) {
            try {
                return Files.readString(path)
            } catch (_: IOException) {
                sleep(RETRY_DELAY_MILLIS)
            }
        }
        return try {
            Files.readString(path)
        } catch (_: IOException) {
            throw AppException.InstallationUnreadable()
        }
    }

    /** Anything at the path, a link included, even a broken one: never replaced. */
    private fun exists(): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun legacy(): String? = settings.getStringOrNull(SettingsInstallationIdentity.KEY)?.takeIf { INSTALLATION_ID.matches(it) }

    /**
     * Writes [id] to a temporary file beside it (owner-only, synced), then publishes it complete without replacement: a
     * hard link on macOS and Linux, a rename on Windows (`MoveFileEx` without `REPLACE_EXISTING`). False when a file is
     * there already.
     */
    private fun publish(id: String): Boolean {
        val temporary = path.resolveSibling("$FILE.${SecureRandom().nextLong().toULong().toString(RADIX)}.tmp")
        try {
            // A new file only: never a file or a link already at that name.
            val options: Array<FileAttribute<*>> = if (temporary.fileSystem.supportedFileAttributeViews().contains("posix")) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                emptyArray()
            }
            FileChannel.open(temporary, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *options).use { channel ->
                val buffer = ByteBuffer.wrap(id.encodeToByteArray())
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            return try {
                if (os == DesktopEnvironment.Os.WINDOWS) Files.move(temporary, path) else Files.createLink(path, temporary)
                true
            } catch (_: FileAlreadyExistsException) {
                false
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val FILE = "installation-id"
        const val MAX_BYTES = 64L
        const val READ_ATTEMPTS = 20
        const val RETRY_DELAY_MILLIS = 25L
        const val RADIX = 36
    }
}
