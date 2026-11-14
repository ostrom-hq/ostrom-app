// SPDX-License-Identifier: Apache-2.0
// The storing part follows TeamCode's companion SignInUseCase (Apache-2.0); see NOTICE.
package run.ostrom.app.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.models.requireSupportedApi
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.services.unlockKeyEntry

/**
 * Keeps a session the server just created. Its capabilities are read with it first, and a server whose API this app
 * does not speak is refused, the session revoked instead of stored. The organization the calls act in is the only one
 * the person belongs to, or the one `me` names; with several and none named, the person chooses it in Settings. The
 * token (and a phone's unlock key) go to the [CredentialStore] and the metadata to the [AccountRepository]; signing in
 * again as the same login on the same server replaces that account and revokes the session it replaces.
 */
class CompleteSignInUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(server: ServerInfo, session: NewSession, method: SignInMethod): Account {
        val capabilities = try {
            auth.capabilities(server.origin, session.token).also { requireSupportedApi(it.apiVersion) }
        } catch (error: Throwable) {
            // Not kept (an API this app does not speak, or no answer): revoked instead of left valid.
            auth.abandon(server.origin, session.token)
            throw error
        }
        val existing = accounts.accounts.value.firstOrNull { it.origin == server.origin && it.login == session.login }
        val organization = organizationOf(server.origin, session.token)
        val account = Account(
            id = existing?.id ?: ids.newId(),
            origin = server.origin,
            login = session.login,
            name = session.name,
            organizationId = organization?.id ?: existing?.organizationId,
            organizationName = organization?.name ?: existing?.organizationName,
            expiresAt = session.expiresAt,
            serverVersion = capabilities.serverVersion,
            method = method,
            // The same membership while the organization is the same: the pushes name it.
            memberId = existing?.memberId?.takeIf { existing.organizationId == (organization?.id ?: existing.organizationId) },
        )
        val replaced = existing?.let { readQuietly(it.id) }
        // The token first: an account is never listed as signed in without its credential.
        try {
            credentials.write(account.id, session.token)
            val unlock = unlockKeyEntry(account.id)
            if (session.unlockKey != null) credentials.write(unlock, session.unlockKey) else credentials.delete(unlock)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            auth.abandon(server.origin, session.token)
            throw AppException.CredentialStorage()
        }
        accounts.save(account)
        replaced?.takeIf { it != session.token }?.let { auth.abandon(server.origin, it) }
        return account
    }

    /** The only organization, else the one `me` names, else none; a server that cannot say leaves it to Settings. */
    private suspend fun organizationOf(origin: String, token: String): Organization? {
        val organizations = try {
            auth.organizations(origin, token)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        organizations.singleOrNull()?.let { return it }
        return try {
            auth.me(origin, token).organization
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readQuietly(id: String): String? = try {
        credentials.read(id)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
