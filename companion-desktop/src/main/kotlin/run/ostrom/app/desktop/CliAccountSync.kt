// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../CliAccountSync.kt; see NOTICE.
package run.ostrom.app.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.unlockKeyEntry

/**
 * Keeps the desktop's CLI account ([Account.CLI_ACCOUNT_ID]) equal to what the `ostrom` CLI says: added when the CLI
 * signs in, described again when it signs in to another server, removed when it signs out. The CLI's two files are
 * checked every [pollMillis] by their stamps ([CliFiles.stamps]: modification time, size and identity, a few bytes of
 * metadata); only a change reads them, then the CLI's token, then the person with that token (`GET /auth/me` and
 * `GET /capabilities`). A `401` lists the account as signed out; any other failure is tried again after [retryMillis].
 * The same person on the same server, signed in in the app before, is merged into the CLI account, so one person and
 * server stay one account. The app never writes the CLI's files or its secret.
 */
class CliAccountSync(
    private val files: CliFiles,
    private val keyring: CliKeyring,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
    private val auth: AuthRepository,
    private val clock: Clock,
    private val pollMillis: Long = 2_000,
    private val retryMillis: Long = 60_000,
) {
    private val mutex = Mutex()
    private var seen: CliFiles.Stamps? = null
    private var retryAt = 0L

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                sync()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The next tick tries again.
            }
            delay(pollMillis)
        }
    }

    /** Applies the CLI's files once if they changed since the last call, or a failed description is due again. */
    suspend fun sync() = mutex.withLock {
        val stamps = withContext(Dispatchers.IO) { files.stamps() }
        if (seen == stamps && (retryAt == 0L || clock.nowMillis() < retryAt)) return@withLock
        val signIn = withContext(Dispatchers.IO) { files.signIn() }
        apply(signIn)
        seen = stamps
    }

    private suspend fun apply(signIn: CliSignIn?) {
        val current = accounts.find(Account.CLI_ACCOUNT_ID)
        val token = signIn?.let { cli ->
            try {
                withContext(Dispatchers.IO) { keyring.token(cli.server) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A locked secret store: the CLI may well be signed in, so nothing changes until it answers.
                retryAt = clock.nowMillis() + retryMillis
                return
            }
        }
        retryAt = 0L
        if (signIn == null || token == null) {
            if (current != null) {
                auth.release(Account.CLI_ACCOUNT_ID)
                accounts.remove(Account.CLI_ACCOUNT_ID)
            }
            return
        }
        val (member, serverVersion) = try {
            auth.me(signIn.server, token) to auth.capabilities(signIn.server, token).serverVersion
        } catch (error: CancellationException) {
            throw error
        } catch (_: AppException.Unauthorized) {
            // A token the server no longer knows: listed as signed out if the account exists, read again once the CLI's files change.
            current?.takeIf { it.origin == signIn.server && it.signedIn }?.let { accounts.update(it.copy(signedIn = false)) }
            return
        } catch (_: Exception) {
            retryAt = clock.nowMillis() + retryMillis
            return
        }
        if (current != null && current.origin != signIn.server) auth.release(Account.CLI_ACCOUNT_ID)
        val active = accounts.activeAccountId.value
        val duplicate = accounts.accounts.value.firstOrNull { !it.usedByCli && it.origin == signIn.server && it.login == member.login }
        val account = describe(signIn.server, member, serverVersion, current?.takeIf { it.origin == signIn.server && it.login == member.login } ?: duplicate)
        duplicate?.let { merge(it, token) }
        when {
            current == null -> {
                accounts.save(account)
                // The CLI's account joins the list; the account the person was looking at stays the active one.
                if (active != duplicate?.id) active?.let(accounts::setActive)
            }
            else -> {
                accounts.update(account)
                if (active == duplicate?.id) accounts.setActive(account.id)
            }
        }
    }

    /** The CLI's account for [member], keeping the organization [previous] chose on the same server. */
    private fun describe(server: String, member: Member, serverVersion: String, previous: Account?): Account {
        val organization = previous?.organizationId?.let { it to previous.organizationName } ?: member.organization?.let { it.id to it.name }
        return Account(
            id = Account.CLI_ACCOUNT_ID,
            origin = server,
            login = member.login,
            name = previous?.name,
            organizationId = organization?.first,
            organizationName = organization?.second,
            // The CLI's files say no expiry: the server's 401 is what signs the account out.
            expiresAt = null,
            serverVersion = serverVersion,
            method = SignInMethod.DEVICE,
            signedIn = true,
            memberId = if (organization?.first == member.organization?.id) member.memberId else previous?.memberId,
        )
    }

    /** The same person on the same server, signed in in the app before the CLI was: one account, the CLI's. */
    private suspend fun merge(duplicate: Account, cliToken: String) {
        val token = try { credentials.read(duplicate.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        for (entry in listOf(duplicate.id, unlockKeyEntry(duplicate.id))) {
            try { credentials.delete(entry) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        }
        auth.release(duplicate.id)
        accounts.remove(duplicate.id)
        // Its own session is no longer needed: revoked, unless it is the very session the CLI now uses.
        token?.takeIf { it != cliToken }?.let { auth.abandon(duplicate.origin, it) }
    }
}
