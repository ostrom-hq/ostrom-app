// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.di

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.core.module.Module
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import run.ostrom.app.FakeServer
import run.ostrom.app.ORIGIN
import run.ostrom.app.TestPlatform
import run.ostrom.app.account
import run.ostrom.app.domain.models.PushMessage
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.services.PushPlatform
import run.ostrom.app.domain.services.SecureRandom
import run.ostrom.app.domain.usecases.push.SyncPushUseCase
import run.ostrom.app.infrastructure.credentials.KeyringCredentialStore
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.system.DesktopPlatformInfo
import run.ostrom.app.infrastructure.system.JavaSecureRandom
import run.ostrom.app.presentation.navigation.LoginRoute
import run.ostrom.app.presentation.viewmodels.LoginViewModel
import run.ostrom.app.demo.StartDemoUseCase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The wiring: what the modules bind, what each platform adds, and what [startPush] does where a phone has push. */
class ModulesTest {
    private class RecordingPush : PushPlatform {
        override val platform = "android"
        override val locale: String? = null
        override val token: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun publicKey(): String = "key"
        override suspend fun open(payload: String): PushMessage = error("not used")
        val shared = MutableStateFlow<Set<String>?>(null)
        override suspend fun signedIn(accounts: Set<String>) { shared.value = accounts }
    }

    private fun platform(push: PushPlatform? = null, browser: BrowserSignIn? = null): Module = module {
        single<Settings> { MapSettings() }
        single<CredentialStore> { MemoryCredentialStore() }
        single<HttpClientEngineFactory<*>> { FakeServer() }
        single<PlatformInfo> { TestPlatform }
        single<SecureRandom> { SecureRandom { ByteArray(it) } }
        push?.let { bound -> single<PushPlatform> { bound } }
        browser?.let { bound -> single<BrowserSignIn> { bound } }
    }

    // The modules are shared values: an application left open keeps its singletons for the next one that loads them.
    private val started = mutableListOf<KoinApplication>()

    private fun start(declaration: KoinAppDeclaration): Koin = koinApplication(declaration).also { started += it }.koin

    @AfterTest fun stop() {
        started.forEach { it.close() }
        stopKoin()
    }

    @Test fun initKoinStartsTheAppsModulesAroundThePlatformAndRunsTheCallersDeclaration() {
        var declared = false
        val koin = initKoin(platform()) { declared = true }.koin
        assertTrue(declared)
        assertIs<Clock>(koin.get<Clock>())
        assertSame(koin.get<AccountRepository>(), koin.get<AccountRepository>(), "singletons")
        assertEquals(TestPlatform, koin.get<PlatformInfo>(), "the platform's own bindings are the ones used")
    }

    @Test fun initKoinWithoutADeclarationStartsTheSameModules() {
        val koin = initKoin(platform()).koin
        assertEquals(TestPlatform, koin.get<PlatformInfo>())
        assertIs<Clock>(koin.get<Clock>())
    }

    @Test fun theDesktopPlatformModuleBindsTheKeyringPreferencesJdkEngineAndSystemServices() {
        val koin = start { modules(platformModule()) }
        assertIs<PreferencesSettings>(koin.get<Settings>())
        assertIs<KeyringCredentialStore>(koin.get<CredentialStore>())
        assertSame(Java, koin.get<HttpClientEngineFactory<*>>())
        assertSame(DesktopPlatformInfo, koin.get<PlatformInfo>())
        assertSame(JavaSecureRandom, koin.get<SecureRandom>())
        assertNull(koin.getOrNull<BrowserSignIn>(), "a computer signs in with the device flow")
    }

    @Test fun aComputerSignsInWithTheDeviceFlowAndAPhoneWithItsBrowser() {
        val computer = start { modules(appModules(platform())) }
        assertFalse(computer.get<LoginViewModel> { parametersOf(LoginRoute(ORIGIN)) }.usesBrowser)
        val phone = start { modules(appModules(platform(browser = { _, _ -> null }))) }
        assertTrue(phone.get<LoginViewModel> { parametersOf(LoginRoute(ORIGIN)) }.usesBrowser)
    }

    @Test fun withoutAPushPlatformStartPushBindsNothingAndStartsNothing() {
        val koin = start { modules(appModules(platform())) }
        koin.get<AccountRepository>().save(account("a1").copy(organizationId = "org1", memberId = "mem1"))
        startPush(koin)
        assertNull(koin.getOrNull<PushPlatform>())
    }

    private suspend fun sharedWith(push: RecordingPush): Set<String>? = withTimeout(10_000) {
        while (push.shared.value == null) delay(20)
        push.shared.value
    }

    @Test fun aPhoneWithPushTellsItsPlatformWhichAccountsAreSignedIn() = runBlocking<Unit> {
        val push = RecordingPush()
        val koin = start { modules(appModules(platform(push = push))) }
        koin.get<AccountRepository>().save(account("a1").copy(organizationId = "org1", memberId = "mem1"))
        startPush(koin)
        assertEquals(setOf("org1 mem1"), sharedWith(push))
        assertNotNull(koin.get<SyncPushUseCase>())
    }

    @Test fun theDemoAccountIsNeverSharedWithThePushPlatform() = runBlocking<Unit> {
        val push = RecordingPush()
        val koin = start { modules(appModules(platform(push = push))) }
        koin.get<StartDemoUseCase>().invoke()
        startPush(koin)
        assertEquals(emptySet(), sharedWith(push), "the demo account is left out")
    }
}
