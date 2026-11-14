// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../di/UpdateModule.kt; see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.repositories.UpdatePreferences
import run.ostrom.app.infrastructure.settings.SettingsUpdatePreferences
import run.ostrom.app.presentation.viewmodels.UpdateBannerViewModel

/** The update banner; included by the desktop's module, which provides the [run.ostrom.app.domain.services.CliUpdater]. */
val updateModule = module {
    single<UpdatePreferences> { SettingsUpdatePreferences(get()) }
    viewModel { UpdateBannerViewModel(get(), get(), get()) }
}
