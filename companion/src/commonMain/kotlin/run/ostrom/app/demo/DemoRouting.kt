// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../demo/DemoRouting.kt; see NOTICE.
package run.ostrom.app.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.services.CredentialStore

/**
 * The device's accounts, with the demo account while the demo runs. The demo exists only while no real account does:
 * a real account saved ends it, and removing the demo account (signing out) drops everything it held. The demo account
 * is never written to [real], so nothing of it outlives the app's process.
 */
class DemoAccountRepository(private val real: AccountRepository, private val demo: DemoMode) : AccountRepository {
    private val list = MutableStateFlow(currentList())
    private val active = MutableStateFlow(currentActive())
    override val accounts: StateFlow<List<Account>> = list.asStateFlow()
    override val activeAccountId: StateFlow<String?> = active.asStateFlow()

    private fun currentList() = listOfNotNull(demo.account) + real.accounts.value
    private fun currentActive() = if (demo.active) DemoContent.ACCOUNT_ID else real.activeAccountId.value

    private fun refresh() {
        list.value = currentList()
        active.value = currentActive()
    }

    /** Starts the demo on its own account; never while a real account exists. */
    fun startDemo(): Account {
        check(real.accounts.value.isEmpty()) { "The demo starts only on a device without accounts." }
        return demo.start().also { refresh() }
    }

    override fun save(account: Account) {
        if (isDemo(account.id)) {
            demo.store().account.value = account
        } else {
            demo.end()
            real.save(account)
        }
        refresh()
    }

    override fun update(account: Account) {
        if (isDemo(account.id)) {
            if (demo.active) demo.store().account.value = account
        } else {
            real.update(account)
        }
        refresh()
    }

    override fun remove(id: String) {
        if (isDemo(id)) demo.end() else real.remove(id)
        refresh()
    }

    override fun setActive(id: String) {
        if (!isDemo(id)) real.setActive(id)
        refresh()
    }
}

/** The platform's secure store for real accounts; the demo account has no token, and none is read, written or deleted for it. */
class DemoCredentialStore(private val real: CredentialStore) : CredentialStore {
    override val persistent: Boolean get() = real.persistent

    // Not a credential: the account checks only see that the demo account is signed in.
    override suspend fun read(accountId: String): String? = if (isDemoEntry(accountId)) "demo" else real.read(accountId)

    override suspend fun write(accountId: String, token: String) {
        if (!isDemoEntry(accountId)) real.write(accountId, token)
    }

    override suspend fun delete(accountId: String) {
        if (!isDemoEntry(accountId)) real.delete(accountId)
    }

    /** The demo account's entries: its token and its unlock key. */
    private fun isDemoEntry(entry: String) = entry.substringBefore('.') == DemoContent.ACCOUNT_ID
}

class DemoAwareAuthRepository(private val real: AuthRepository, private val demo: AuthRepository) : AuthRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real

    // The demo's origin is never asked: it does not resolve, and nothing of the demo may reach a network.
    private fun at(origin: String) = if (origin == DemoContent.ORIGIN) demo else real

    override suspend fun discover(origin: String) = at(origin).discover(origin)
    override fun prepareBrowserSignIn(origin: String, redirectUri: String, state: String) = at(origin).prepareBrowserSignIn(origin, redirectUri, state)
    override suspend fun exchangeCode(origin: String, code: String, codeVerifier: String, redirectUri: String) =
        at(origin).exchangeCode(origin, code, codeVerifier, redirectUri)
    override suspend fun startDeviceLogin(server: ServerInfo): DeviceLogin = at(server.origin).startDeviceLogin(server)
    override suspend fun pollDeviceLogin(origin: String, deviceCode: String) = at(origin).pollDeviceLogin(origin, deviceCode)
    override suspend fun capabilities(origin: String, token: String) = at(origin).capabilities(origin, token)
    override suspend fun capabilities(accountId: String) = of(accountId).capabilities(accountId)
    override suspend fun organizations(origin: String, token: String) = at(origin).organizations(origin, token)
    override suspend fun organizations(accountId: String) = of(accountId).organizations(accountId)
    override suspend fun me(origin: String, token: String) = at(origin).me(origin, token)
    override suspend fun me(accountId: String) = of(accountId).me(accountId)
    override suspend fun logout(accountId: String) = of(accountId).logout(accountId)
    override suspend fun release(accountId: String) = of(accountId).release(accountId)
    override fun abandon(origin: String, token: String) = at(origin).abandon(origin, token)
}

class DemoAwareFeaturesRepository(private val real: FeaturesRepository, private val demo: FeaturesRepository) : FeaturesRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun capabilities(accountId: String, refresh: Boolean) = of(accountId).capabilities(accountId, refresh)
    override fun cached(accountId: String) = of(accountId).cached(accountId)
}
