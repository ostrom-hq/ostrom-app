// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import com.russhwolf.settings.MapSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import com.russhwolf.settings.Settings
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.services.InstallationIdentity
import run.ostrom.app.infrastructure.settings.SettingsInstallationIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedInstallationIdentityTest {
    private val os = DesktopEnvironment.Os.current()
    private val posix = os != DesktopEnvironment.Os.WINDOWS
    private val ids = object : IdGenerator {
        private var next = 0
        override fun newId(): String = "00000000-0000-4000-8000-00000000000${next++}"
    }

    private fun identity(directory: File, settings: Settings = MapSettings(), locker: CliFileLocker = CliFileLocker.of(os), sleep: suspend (Long) -> Unit = {}) =
        SharedInstallationIdentity(directory.path, settings, ids, os, locker, sleep)

    private fun File.id() = File(this, "installation-id")

    @Test
    fun theAppCreatesTheCliFileOnceOwnerOnlyAndKeepsIt() = withTemporaryDirectory { home ->
        runTest {
            val directory = File(home, "ostrom")
            val id = identity(directory).id()
            assertEquals("00000000-0000-4000-8000-000000000000", id)
            assertEquals(id, directory.id().readText(), "The CLI reads the same id, without a newline")
            assertEquals(id, identity(directory).id(), "Read again by another start, nothing drawn")
            assertTrue(File(directory, "installation-id.lock").exists(), "Created under the CLI's lock")
            assertEquals(listOf("installation-id", "installation-id.lock"), directory.list()!!.sorted(), "No temporary file left")
            if (posix) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.id().toPath())))
        }
    }

    @Test
    fun theCliIdWinsOverTheOneTheAppKeptAndAKeptOneIsPublishedOnlyWithoutIt() = withTemporaryDirectory { directory ->
        runTest {
            val kept = MapSettings(SettingsInstallationIdentity.KEY to "11111111-2222-4333-8444-555555555555")
            directory.id().writeText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
            assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", identity(directory, kept).id())
            directory.id().delete()
            assertEquals("11111111-2222-4333-8444-555555555555", identity(directory, kept).id(), "The app's device keeps its name and sessions")
            assertEquals("11111111-2222-4333-8444-555555555555", directory.id().readText())
        }
    }

    @Test
    fun anIdTheCliWroteWhileTheAppWaitedForTheLockWins() = withTemporaryDirectory { directory ->
        runTest {
            val cliFirst = CliFileLocker { lockFile ->
                // The CLI held the lock and created its id; the app takes the lock after it.
                directory.id().writeText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
                CliFileLocker.of(os).lock(lockFile)
            }
            assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", identity(directory, locker = cliFirst).id())
            assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", directory.id().readText())
        }
    }

    @Test
    fun aFileThatIsNotAnIdIsRefusedAndNeverOverwritten() = withTemporaryDirectory { directory ->
        runTest {
            directory.id().writeText("not an id\n")
            assertFailsWith<AppException.InstallationUnreadable> { identity(directory).id() }
            assertEquals("not an id\n", directory.id().readText())
            directory.id().writeText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\n")
            assertFailsWith<AppException.InstallationUnreadable> { identity(directory).id() }
            directory.id().delete()
            directory.id().mkdir()
            assertFailsWith<AppException.InstallationUnreadable> { identity(directory).id() }
            assertTrue(directory.id().isDirectory)
        }
    }

    @Test
    fun aSymbolicLinkIsRefusedEvenBroken() = withTemporaryDirectory { directory ->
        if (!posix) return@withTemporaryDirectory
        runTest {
            val target = File(directory, "elsewhere")
            Files.createSymbolicLink(directory.id().toPath(), target.toPath())
            assertFailsWith<AppException.InstallationUnreadable> { identity(directory).id() }
            assertTrue(Files.isSymbolicLink(directory.id().toPath()))
            assertTrue(!target.exists(), "Nothing written through the link")
            target.writeText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
            assertFailsWith<AppException.InstallationUnreadable> { identity(directory).id() }
        }
    }

    @Test
    fun aReadIsTriedAgainTwentyTimesTwentyFiveMillisecondsApart() = withTemporaryDirectory { directory ->
        if (!posix) return@withTemporaryDirectory
        val file = directory.id().toPath()
        directory.id().writeText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
        // A superuser reads it anyway: nothing to try again then.
        if (Files.isReadable(file)) return@withTemporaryDirectory
        runTest {
            val pauses = mutableListOf<Long>()
            val publishing = identity(directory) { pause ->
                pauses += pause
                if (pauses.size == 3) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
            assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", publishing.id())
            assertEquals(listOf(25L, 25L, 25L), pauses)

            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
            pauses.clear()
            assertFailsWith<AppException.InstallationUnreadable> { identity(directory) { pauses += it }.id() }
            assertEquals(19, pauses.size, "20 attempts")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun aLockHeldPastTheWaitIsSaidAsBusyAndNothingIsWritten() = withTemporaryDirectory { directory ->
        runTest {
            val stuck = CliFileLocker { throw CliLockBusyException(it) }
            assertFailsWith<AppException.InstallationBusy> { identity(directory, locker = stuck).id() }
            assertTrue(!directory.id().exists())
        }
    }

    @Test
    fun aLockThatCannotBeTakenIsSaidAndNothingIsWritten() = withTemporaryDirectory { directory ->
        runTest {
            val refusing = CliFileLocker { throw IOException("read-only") }
            assertFailsWith<AppException.InstallationUnreadable> { identity(directory, locker = refusing).id() }
            assertTrue(!directory.id().exists())
        }
    }

    @Test
    fun twoFirstRunsAtOnceAgreeOnOneId() = withTemporaryDirectory { directory ->
        val ids = runBlocking {
            (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) { identity(directory).id() } }.awaitAll()
        }
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), directory.id().readText())
    }

    @Test
    fun theDesktopSharesTheCliIdWhenItKnowsItsFolder() = withTemporaryDirectory { home ->
        val environment = DesktopEnvironment(mapOf("HOME" to home.path, "OSTROM_HOME" to "${home.path}/ostrom"), os = os)
        val shared = module {
            single<Settings> { MapSettings() }
            single<IdGenerator> { ids }
        }
        val koin = koinApplication { modules(shared, desktopOverrides(environment, keyring = null)) }.koin
        try {
            assertIs<SharedInstallationIdentity>(koin.get<InstallationIdentity>())
            val id = runBlocking { koin.get<InstallationIdentity>().id() }
            assertEquals(id, File(home, "ostrom/installation-id").readText())
        } finally {
            koin.close()
        }
        val nowhere = koinApplication { modules(shared, desktopOverrides(DesktopEnvironment(emptyMap(), os = os), keyring = null)) }.koin
        try {
            assertTrue(nowhere.getOrNull<InstallationIdentity>() == null, "Without the CLI's folder the shared definition stays")
        } finally {
            nowhere.close()
        }
    }
}
