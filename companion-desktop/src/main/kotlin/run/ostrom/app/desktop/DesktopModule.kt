// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../DesktopModule.kt; see NOTICE.
package run.ostrom.app.desktop

import org.koin.core.module.Module
import org.koin.dsl.module
import run.ostrom.app.demo.DemoCredentialStore
import run.ostrom.app.di.platformModule
import run.ostrom.app.di.updateModule
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.services.CliSession
import run.ostrom.app.domain.services.CliUpdater
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.InstallationIdentity
import run.ostrom.app.infrastructure.credentials.KeyringBackend
import run.ostrom.app.infrastructure.credentials.KeyringCredentialStore

/**
 * The desktop's platform module: the JVM one of `:companion` (keyring, preferences, the JDK HTTP engine), the update
 * banner, then what replaces or adds to it on a computer. Loaded after the shared modules, its definitions override
 * theirs: the CLI's installation id, account and token, the CLI's sign-out and update.
 *
 * The CLI's account needs the OS secret store, where the CLI keeps its token: none on Windows (java-keyring does not
 * read the CLI's Credential Manager entry) nor on a Linux without Secret Service.
 */
fun desktopModule(environment: DesktopEnvironment): Module {
    val keyring = if (environment.os == DesktopEnvironment.Os.WINDOWS) null else KeyringCredentialStore.openBackend()
    return module { includes(platformModule(), updateModule, desktopOverrides(environment, keyring)) }
}

internal fun desktopOverrides(environment: DesktopEnvironment, keyring: KeyringBackend?): Module = module {
    single { environment }
    single<CliProcesses> { SystemCliProcesses(get()) }
    single { CliLocator(get(), get()) }
    single<CliUpdater> { DesktopCliUpdater(get(), get()) }
    single<CliSession> { DesktopCliSession(get(), get()) }
    // The CLI's installation id, shared: the app and the CLI of this computer are one device.
    environment.cliDirectory?.let { directory ->
        single<InstallationIdentity> { SharedInstallationIdentity(directory, get(), get(), environment.os) }
    }
    if (keyring != null) {
        val cli = CliKeyring.of(keyring)
        single { CliFiles(environment.cliDirectory, environment["OSTROM_URL"]) }
        single<CredentialStore> {
            val scope = this
            CliCredentialStore(KeyringCredentialStore(keyring), get(), cli) { id -> scope.get<AccountRepository>().find(id)?.origin }
        }
        single { CliAccountSync(get(), cli, get(), get<DemoCredentialStore>(), get(), get()) }
    }
}
