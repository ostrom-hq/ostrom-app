// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.demo.DemoCredentialStore
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.usecases.auth.BrowserSignInUseCase
import run.ostrom.app.presentation.navigation.LoginRoute
import run.ostrom.app.presentation.viewmodels.AddServerViewModel
import run.ostrom.app.presentation.viewmodels.HomeViewModel
import run.ostrom.app.presentation.viewmodels.LoginViewModel
import run.ostrom.app.presentation.viewmodels.RootViewModel
import run.ostrom.app.presentation.viewmodels.SettingsViewModel

val presentationModule = module {
    viewModel { RootViewModel(get(), get()) }
    viewModel { AddServerViewModel(get(), get(), get()) }
    viewModel { (route: LoginRoute) ->
        // A phone signs in with its browser; a computer (no BrowserSignIn bound) with the device flow.
        val browser = if (getOrNull<BrowserSignIn>() != null) get<BrowserSignInUseCase>() else null
        LoginViewModel(route, get(), browser, get())
    }
    viewModel { HomeViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get<DemoCredentialStore>(), get()) }
}
