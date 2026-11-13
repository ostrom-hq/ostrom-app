// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../presentation/screens/SessionsScreen.kt (rememberNow, relativeTime); see NOTICE.
package run.ostrom.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.time_days
import run.ostrom.app.resources.time_hours
import run.ostrom.app.resources.time_just_now
import run.ostrom.app.resources.time_minutes

/** The current time, refreshed every 30 seconds for relative dates. */
@Composable
fun rememberNow(clock: Clock = koinInject()): Long {
    val now by produceState(clock.nowMillis(), clock) {
        while (true) {
            delay(30_000)
            value = clock.nowMillis()
        }
    }
    return now
}

/** How long ago [at] was: "just now", "5 min", "3 h", "2 d" in the app's language. */
@Composable
fun relativeTime(at: Long, now: Long): String {
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(Res.string.time_just_now)
        minutes < 60 -> stringResource(Res.string.time_minutes, minutes.toInt())
        minutes < 48 * 60 -> stringResource(Res.string.time_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.time_days, (minutes / 1440).toInt())
    }
}
