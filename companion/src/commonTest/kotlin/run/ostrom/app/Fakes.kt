// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app

import run.ostrom.app.domain.repositories.Installation
import run.ostrom.app.domain.services.InstallationIdentity
import run.ostrom.app.domain.services.PlatformInfo
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.repositories.EventRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.BrowserAttempt
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository

const val ORIGIN = "https://ostrom.example.org"
val SERVER = ServerInfo(ORIGIN)
val CAPABILITIES = ServerCapabilities(serverVersion = "1.4.0", apiVersion = 1, features = setOf("gates.v1"))

fun accounts() = SettingsAccountRepository(MapSettings())

fun account(id: String = "a1", login: String = "ada", origin: String = ORIGIN, signedIn: Boolean = true, expiresAt: Long? = null, memberId: String? = null) =
    Account(id = id, origin = origin, login = login, signedIn = signedIn, expiresAt = expiresAt, memberId = memberId)

class FixedClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMillis(): Long = now
}

/** An [InstallationIdentity] that always answers [id]. */
class FixedInstallation(private val id: String = "0f3c9a1e-2b4d-4c6e-8a0b-1c2d3e4f5a6b") : InstallationIdentity {
    override suspend fun id(): String = id
}

/** A computer named `studio` on macOS. */
object FakePlatform : PlatformInfo {
    override val platform = "macOS"
    override val deviceModel = "studio"
    override val appVersion = "1.0.0"
}

class SequenceIds : IdGenerator {
    private var next = 0
    override fun newId(): String = "id-${++next}"
}

object CountingRandom : SecureRandom {
    override fun nextBytes(count: Int): ByteArray = ByteArray(count) { it.toByte() }
}

/** An [AuthRepository] whose answers a test sets, recording what was asked. */
class FakeAuthRepository : AuthRepository {
    var server: ServerInfo = SERVER
    var discoverError: Exception? = null
    var capabilities: ServerCapabilities = CAPABILITIES
    var capabilitiesError: Exception? = null
    var organizations: List<Organization> = emptyList()
    var organizationsError: Exception? = null
    var member: Member = Member("ada", null)
    var meError: Exception? = null
    var exchanged: NewSession = NewSession("osm_token", "ada", "Ada Lovelace", null, "unlock-key")
    var polls: MutableList<() -> DevicePoll> = mutableListOf()
    var logoutError: Exception? = null
    val abandoned = mutableListOf<String>()
    val loggedOut = mutableListOf<String>()
    val released = mutableListOf<String>()
    val exchanges = mutableListOf<Triple<String, String, String>>()
    var preparedState: String? = null
    val installations = mutableListOf<Installation?>()

    override suspend fun discover(origin: String): ServerInfo {
        discoverError?.let { throw it }
        return server.copy(origin = origin)
    }

    override fun prepareBrowserSignIn(origin: String, redirectUri: String, state: String): BrowserAttempt {
        preparedState = state
        return BrowserAttempt("$origin/api/v1/auth/github/start?state=$state", "verifier")
    }

    override suspend fun exchangeCode(origin: String, code: String, codeVerifier: String, redirectUri: String, installation: Installation?): NewSession {
        exchanges += Triple(code, codeVerifier, redirectUri)
        installations += installation
        return exchanged
    }

    override suspend fun startDeviceLogin(server: ServerInfo, installation: Installation?): DeviceLogin =
        installations.add(installation).let { DeviceLogin(server, "ABCD-EFGH", "https://github.com/login/device", "device-code", intervalMillis = 5_000, expiresInMillis = 60_000) }

    override suspend fun pollDeviceLogin(origin: String, deviceCode: String): DevicePoll = polls.removeFirst().invoke()

    override suspend fun capabilities(origin: String, token: String): ServerCapabilities {
        capabilitiesError?.let { throw it }
        return capabilities
    }

    override suspend fun capabilities(accountId: String): ServerCapabilities = capabilities("", "")

    override suspend fun organizations(origin: String, token: String): List<Organization> = organizations
    override suspend fun organizations(accountId: String): List<Organization> = organizationsError?.let { throw it } ?: organizations

    override suspend fun me(origin: String, token: String): Member {
        meError?.let { throw it }
        return member
    }

    override suspend fun me(accountId: String): Member = me("", "")

    override suspend fun logout(accountId: String) {
        logoutError?.let { throw it }
        loggedOut += accountId
    }

    override suspend fun release(accountId: String) {
        released += accountId
    }

    override fun abandon(origin: String, token: String) {
        abandoned += token
    }
}

/** A server whose capabilities are [features]; every test sets the ones it needs. */
class FakeFeatures(var features: Set<String> = emptySet()) : run.ostrom.app.domain.repositories.FeaturesRepository {
    override suspend fun capabilities(accountId: String, refresh: Boolean) = ServerCapabilities("1.4.0", 1, features)
    override fun cached(accountId: String) = ServerCapabilities("1.4.0", 1, features)
}

/** The organization's feed of a test: [changes] is what every connection streams; [asked] records the types of each connection. */
class FakeEventRepository(var changes: Flow<LiveUpdate<OrgChange>> = emptyFlow()) : EventRepository {
    val asked = mutableListOf<Set<String>>()
    override fun changes(accountId: String, types: Set<String>): Flow<LiveUpdate<OrgChange>> { asked += types; return changes }
}

/** An [AccountGuard] over fakes: a `401` checks the account once and goes on. */
fun fakeGuard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts(), MemoryCredentialStore(), FixedClock()))

/** The member's devices of a test: what each call answers, and the calls asked, in order. */
class FakeDeviceRepository : run.ostrom.app.domain.repositories.DeviceRepository {
    val calls = mutableListOf<String>()
    var devicesResult: suspend () -> List<run.ostrom.app.domain.models.HostDevice> = { emptyList() }
    var foldersResult: suspend (String) -> run.ostrom.app.domain.models.DeviceFolders = { run.ostrom.app.domain.models.DeviceFolders(true, emptyList()) }
    var resumableResult: suspend (String, String, String?) -> run.ostrom.app.domain.models.ResumableSessions = { _, _, _ -> run.ostrom.app.domain.models.ResumableSessions(emptyList()) }

    override suspend fun onlineDevices(accountId: String): List<run.ostrom.app.domain.models.HostDevice> { calls += "devices"; return devicesResult() }
    override suspend fun folders(accountId: String, deviceId: String): run.ostrom.app.domain.models.DeviceFolders { calls += "folders $deviceId"; return foldersResult(deviceId) }
    override suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: String?): run.ostrom.app.domain.models.ResumableSessions {
        calls += "resumable $deviceId $folderId $limit before=$before"
        return resumableResult(deviceId, folderId, before)
    }
}

fun hostDevice(id: String, name: String = "Ada's MacBook", current: Boolean = false) = run.ostrom.app.domain.models.HostDevice(id, name, "macos", "15.1", current)
