// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../demo/DemoRepositories.kt; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.BrowserAttempt
import run.ostrom.app.domain.repositories.DevicePoll
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.NewSession

/** The demo server: it signs nobody in (the demo account exists already) and answers its account from memory. */
class DemoAuthRepository(private val demo: DemoMode) : AuthRepository {
    override suspend fun discover(origin: String): ServerInfo = throw AppException.NotOstrom()
    override fun prepareBrowserSignIn(origin: String, redirectUri: String, state: String): BrowserAttempt = throw AppException.NotOstrom()
    override suspend fun exchangeCode(origin: String, code: String, codeVerifier: String, redirectUri: String): NewSession = throw AppException.NotOstrom()
    override suspend fun startDeviceLogin(server: ServerInfo): DeviceLogin = throw AppException.NotOstrom()
    override suspend fun pollDeviceLogin(origin: String, deviceCode: String): DevicePoll = throw AppException.NotOstrom()
    override suspend fun capabilities(origin: String, token: String): ServerCapabilities = throw AppException.NotOstrom()
    override suspend fun capabilities(accountId: String): ServerCapabilities =
        ServerCapabilities(DemoContent.SERVER_VERSION, Features.SUPPORTED_API_VERSION, DemoContent.features).also { demo.store() }
    override suspend fun organizations(origin: String, token: String): List<Organization> = throw AppException.NotOstrom()
    override suspend fun organizations(accountId: String): List<Organization> =
        demo.store().account.value.let { listOf(Organization(it.organizationId.orEmpty(), it.organizationName.orEmpty())) }
    override suspend fun me(origin: String, token: String): Member = throw AppException.NotOstrom()
    override suspend fun me(accountId: String): Member = demo.store().account.value.let { Member(it.login, null) }
    override suspend fun logout(accountId: String) = Unit
    override suspend fun release(accountId: String) = Unit
    override fun abandon(origin: String, token: String) = Unit
}

class DemoFeaturesRepository(private val auth: DemoAuthRepository) : FeaturesRepository {
    override suspend fun capabilities(accountId: String, refresh: Boolean): ServerCapabilities = auth.capabilities(accountId)
    override fun cached(accountId: String): ServerCapabilities? =
        ServerCapabilities(DemoContent.SERVER_VERSION, Features.SUPPORTED_API_VERSION, DemoContent.features)
}
