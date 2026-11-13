// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app

import com.russhwolf.settings.MapSettings
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

fun account(id: String = "a1", login: String = "ada", origin: String = ORIGIN, signedIn: Boolean = true, expiresAt: Long? = null) =
    Account(id = id, origin = origin, login = login, signedIn = signedIn, expiresAt = expiresAt)

class FixedClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMillis(): Long = now
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

    override suspend fun discover(origin: String): ServerInfo {
        discoverError?.let { throw it }
        return server.copy(origin = origin)
    }

    override fun prepareBrowserSignIn(origin: String, redirectUri: String, state: String): BrowserAttempt {
        preparedState = state
        return BrowserAttempt("$origin/api/v1/auth/github/start?state=$state", "verifier")
    }

    override suspend fun exchangeCode(origin: String, code: String, codeVerifier: String, redirectUri: String): NewSession {
        exchanges += Triple(code, codeVerifier, redirectUri)
        return exchanged
    }

    override suspend fun startDeviceLogin(server: ServerInfo): DeviceLogin =
        DeviceLogin(server, "ABCD-EFGH", "https://github.com/login/device", "device-code", intervalMillis = 5_000, expiresInMillis = 60_000)

    override suspend fun pollDeviceLogin(origin: String, deviceCode: String): DevicePoll = polls.removeFirst().invoke()

    override suspend fun capabilities(origin: String, token: String): ServerCapabilities {
        capabilitiesError?.let { throw it }
        return capabilities
    }

    override suspend fun capabilities(accountId: String): ServerCapabilities = capabilities("", "")

    override suspend fun organizations(origin: String, token: String): List<Organization> = organizations
    override suspend fun organizations(accountId: String): List<Organization> = organizations

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
