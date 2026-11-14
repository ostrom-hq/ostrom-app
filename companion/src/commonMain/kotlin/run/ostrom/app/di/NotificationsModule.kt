// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/NotificationsModule.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import run.ostrom.app.domain.usecases.notifications.SaveNotificationPreferencesUseCase
import run.ostrom.app.infrastructure.repositories.RemoteNotificationPreferencesRepository
import run.ostrom.app.presentation.navigation.NotificationPreferencesRoute
import run.ostrom.app.presentation.screens.deviceTimeZoneId
import run.ostrom.app.presentation.viewmodels.NotificationPreferencesViewModel

/** The person's notification preferences (`notifications.v1`). */
val notificationsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteNotificationPreferencesRepository(get()) }
    single { LoadNotificationPreferencesUseCase(get(), get(), get()) }
    single { SaveNotificationPreferencesUseCase(get(), get()) }
    viewModel { (route: NotificationPreferencesRoute) -> NotificationPreferencesViewModel(route, get(), get(), ::deviceTimeZoneId) }
}
