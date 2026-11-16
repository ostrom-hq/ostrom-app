// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.desktop

import java.io.File
import java.nio.file.Files
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.BrowserAttempt
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.Installation
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.services.Clock

/** A temporary folder, deleted after [block]. */
fun <T> withTemporaryDirectory(block: (File) -> T): T {
    val directory = Files.createTempDirectory("ostrom-desktop-test").toFile()
    try {
        return block(directory)
    } finally {
        directory.deleteRecursively()
    }
}

class TestClock(var now: Long = 1_000) : Clock {
    override fun nowMillis() = now
}

/** The calls the CLI account makes: `me` and `capabilities` with a token, by server; every other call records itself. */
class RecordingAuth : AuthRepository {
    val calls = mutableListOf<String>()
    /** The person behind each token; a token absent here is refused with [failure]. */
    val members = mutableMapOf<String, Member>()
    var failure: Exception? = null

    override suspend fun me(origin: String, token: String): Member {
        calls += "me $origin $token"
        failure?.let { throw it }
        return members.getValue(token)
    }

    override suspend fun capabilities(origin: String, token: String): ServerCapabilities = ServerCapabilities("2.0.0", 1, emptySet())
    override suspend fun release(accountId: String) { calls += "release $accountId" }
    override fun abandon(origin: String, token: String) { calls += "abandon $origin $token" }
    override suspend fun logout(accountId: String) { calls += "logout $accountId" }

    override suspend fun discover(origin: String): ServerInfo = error("unused")
    override fun prepareBrowserSignIn(origin: String, redirectUri: String, state: String): BrowserAttempt = error("unused")
    override suspend fun exchangeCode(origin: String, code: String, codeVerifier: String, redirectUri: String): NewSession = error("unused")
    override suspend fun startDeviceLogin(server: ServerInfo, installation: Installation?): DeviceLogin = error("unused")
    override suspend fun pollDeviceLogin(origin: String, deviceCode: String): DevicePoll = error("unused")
    override suspend fun capabilities(accountId: String): ServerCapabilities = error("unused")
    override suspend fun organizations(origin: String, token: String): List<Organization> = error("unused")
    override suspend fun organizations(accountId: String): List<Organization> = error("unused")
    override suspend fun me(accountId: String): Member = error("unused")
}

/** What `ostrom login` and `ostrom logout` leave in the CLI's folder, written as the CLI writes them. */
class FakeCli(val directory: File) {
    /** The secret store: the token of each server. */
    val tokens = mutableMapOf<String, String>()
    var locked = false
    val keyring = CliKeyring { server -> if (locked) throw IllegalStateException("locked") else tokens[server] }

    fun login(server: String, token: String, login: String = "ada") {
        directory.mkdirs()
        // The CLI keys its token and its entry by the server without a trailing slash.
        val key = server.trimEnd('/')
        tokens[key] = token
        File(directory, "config.toml").writeText("# ostrom\nserver = \"$server\"\n")
        val entries = (readEntries() + (key to login)).entries.joinToString(",\n") { (s, l) -> "  \"$s\": {\n    \"login\": \"$l\"\n  }" }
        // A new version of the file always differs in size here, so its stamp changes within the same millisecond.
        File(directory, "credentials.json").writeText("{\n$entries\n}\n" + " ".repeat(counter++))
    }

    fun logout(server: String) {
        tokens.remove(server)
        val entries = (readEntries() - server).entries.joinToString(",\n") { (s, l) -> "  \"$s\": {\n    \"login\": \"$l\"\n  }" }
        File(directory, "credentials.json").writeText("{\n$entries\n}\n" + " ".repeat(counter++))
    }

    private var counter = 0

    private fun readEntries(): Map<String, String> {
        val file = File(directory, "credentials.json")
        if (!file.exists()) return emptyMap()
        return Regex(""""([^"]+)": \{\s*"login": "([^"]*)"""").findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
