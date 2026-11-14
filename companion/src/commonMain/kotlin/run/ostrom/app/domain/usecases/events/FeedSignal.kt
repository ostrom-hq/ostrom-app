// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionListSignal and RunsSignal (Apache-2.0), companion/.../domain/usecases/; see NOTICE.
package run.ostrom.app.domain.usecases.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.repositories.FeaturesRepository

/** What a screen that follows the feed does next: load again, apply one change, or show the connection's state. */
sealed interface FeedSignal {
    data object Reload : FeedSignal
    data class Changed(val change: OrgChange) : FeedSignal
    data class Connection(val reconnecting: Boolean) : FeedSignal
}

/**
 * The feed as one screen reads it: from a server without `events.v1` (or with none of the types the app reads), one
 * [FeedSignal.Reload] (the screen loads again on return and on refresh); otherwise the app's one connection
 * ([OrgFeed]): a load once it is open (so nothing changed between loading and following is missed), after a gap, and
 * [FeedSignal.Changed] for each event of [types].
 */
internal fun OrgFeed.signals(accountId: String, features: FeaturesRepository, types: Set<String>): Flow<FeedSignal> = flow {
    val served = features.capabilities(accountId).features
    // No feed, or none of the types the app reads (an empty set would ask the server for every type): load once.
    if (Features.EVENTS !in served || OrgChange.typesFor(served).isEmpty()) { emit(FeedSignal.Reload); return@flow }
    follow(accountId).collect { update ->
        when (update) {
            is LiveUpdate.Opened -> { emit(FeedSignal.Connection(reconnecting = false)); if (!update.resumed) emit(FeedSignal.Reload) }
            LiveUpdate.Gap -> emit(FeedSignal.Reload)
            is LiveUpdate.Item -> if (update.value.type in types) emit(FeedSignal.Changed(update.value))
            is LiveUpdate.Reconnecting -> emit(FeedSignal.Connection(reconnecting = true))
        }
    }
}
