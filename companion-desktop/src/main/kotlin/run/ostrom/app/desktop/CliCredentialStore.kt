// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../CliCredentialStore.kt; see NOTICE.
package run.ostrom.app.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.infrastructure.credentials.KeyringBackend

/**
 * The `ostrom` CLI's device token, read where the CLI keeps it: the OS secret store, service `run.ostrom.cli`, account =
 * the server's URL (ostrom-cli's `Secrets.kt`). java-keyring reads that very entry on macOS (a generic password of that
 * service and account; the Keychain may ask once to let this app read the CLI's item) and on Linux (Secret Service
 * attributes `service` and `account`). Not on Windows, where java-keyring names and encodes Credential Manager entries
 * otherwise: the desktop binds none there. Read only: the app never writes or deletes the CLI's secret.
 */
fun interface CliKeyring {
    /** The CLI's token for [server], null without one. Throws when the secret store refuses (locked). */
    fun token(server: String): String?

    companion object {
        const val SERVICE = "run.ostrom.cli"

        /** The CLI's entries in [backend]. */
        fun of(backend: KeyringBackend): CliKeyring = CliKeyring { server -> backend.get(SERVICE, server)?.takeIf { it.isNotEmpty() } }
    }
}

/**
 * Tokens of the desktop's accounts: the CLI's account ([Account.CLI_ACCOUNT_ID]) reads the CLI's token ([cli]), every
 * other account the app's own keyring ([keyring]), as before. [originOf] gives an account's server: the CLI's token is
 * only ever handed out for the server the account describes and while the CLI still works with that server, so an
 * `ostrom login` to another server never sends its token to the previous one before the account is described again.
 *
 * The CLI's token is kept in memory while the CLI's files stay as they were ([CliFiles.stamps]): the secret store is
 * not asked at every call.
 */
class CliCredentialStore(
    private val keyring: CredentialStore,
    private val files: CliFiles,
    private val cli: CliKeyring,
    private val originOf: (String) -> String?,
) : CredentialStore {
    private data class Cached(val stamps: CliFiles.Stamps, val server: String, val token: String)

    @Volatile private var cached: Cached? = null

    override val persistent: Boolean get() = keyring.persistent

    override suspend fun read(accountId: String): String? {
        if (!accountId.isCli()) return keyring.read(accountId)
        // The CLI holds no unlock key: a computer signs in with the device flow.
        if (accountId != Account.CLI_ACCOUNT_ID) return null
        return withContext(Dispatchers.IO) {
            val stamps = files.stamps()
            cached?.takeIf { it.stamps == stamps && it.server == originOf(accountId) }?.let { return@withContext it.token }
            val signIn = files.signIn() ?: return@withContext null
            val origin = originOf(accountId)
            // Thrown, not null: null would sign the account out, while it only waits to be described again.
            check(origin == null || signIn.server == origin) { "The CLI now works with another server." }
            cli.token(signIn.server)?.also { cached = Cached(stamps, signIn.server, it) }
        }
    }

    /** The CLI's token is the CLI's: only `ostrom login` writes it. */
    override suspend fun write(accountId: String, token: String) {
        check(!accountId.isCli()) { "The CLI's token is written by ostrom login only." }
        keyring.write(accountId, token)
    }

    /** Nothing for the CLI's account: `ostrom logout` forgets the CLI's token ([DesktopCliSession]). */
    override suspend fun delete(accountId: String) {
        if (accountId.isCli()) cached = null else keyring.delete(accountId)
    }

    /** The CLI account's entries: its token, and the unlock key it never has. */
    private fun String.isCli() = substringBefore('.') == Account.CLI_ACCOUNT_ID
}
