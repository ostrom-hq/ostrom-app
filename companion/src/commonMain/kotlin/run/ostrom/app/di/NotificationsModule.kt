// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/NotificationsModule.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import run.ostrom.app.domain.usecases.notifications.LoadNotificationSettingsUseCase
import run.ostrom.app.domain.usecases.notifications.SaveNotificationPreferencesUseCase
import run.ostrom.app.domain.usecases.notifications.SendTestPushUseCase
import run.ostrom.app.domain.usecases.notifications.SetNotificationSettingUseCase
import run.ostrom.app.infrastructure.repositories.RemoteNotificationPreferencesRepository
import run.ostrom.app.infrastructure.repositories.RemoteNotificationSettingsRepository
import run.ostrom.app.presentation.navigation.NotificationPreferencesRoute
import run.ostrom.app.presentation.screens.deviceTimeZoneId
import run.ostrom.app.presentation.viewmodels.NotificationPreferencesViewModel
import run.ostrom.app.presentation.viewmodels.NotificationSettingsViewModel

/** The person's notification preferences and switches per channel and kind (`notifications.v1`), and the test push. */
val notificationsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteNotificationPreferencesRepository(get()) }
    single { LoadNotificationPreferencesUseCase(get(), get(), get()) }
    single { SaveNotificationPreferencesUseCase(get(), get()) }
    viewModel { (route: NotificationPreferencesRoute) -> NotificationPreferencesViewModel(route, get(), get(), ::deviceTimeZoneId) }
    single { RemoteNotificationSettingsRepository(get()) }
    single { LoadNotificationSettingsUseCase(get(), get(), get()) }
    single { SetNotificationSettingUseCase(get(), get()) }
    single { SendTestPushUseCase(get(), get()) }
    viewModel { (route: NotificationPreferencesRoute) -> NotificationSettingsViewModel(route, get(), get(), get()) }
}
