// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt (SessionFeed) (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.dsl.module
import run.ostrom.app.domain.repositories.EventRepository
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.infrastructure.repositories.RemoteEventRepository

/**
 * The organization's feed (`events.v1`): one connection per account for the whole app ([OrgFeed]), which the waits,
 * the sessions, a session's todo and the runs all follow.
 */
val eventsModule = module {
    single<EventRepository> { RemoteEventRepository(get()) }
    single { OrgFeed(get(), get(), get()) }
}
