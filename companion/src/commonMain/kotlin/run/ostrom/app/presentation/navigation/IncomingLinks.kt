// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/navigation/IncomingLinks.kt; see NOTICE.
package run.ostrom.app.presentation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Links handed to the app by the system (a tapped notification, `ostrom://notification?…`), kept until the navigation
 * reads them: a tap that starts the app arrives before its first screen.
 */
object IncomingLinks {
    private val pending = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = pending.asStateFlow()

    /** Called by the platform shells. */
    fun open(link: String) {
        pending.value = link.take(MAX_LINK)
    }

    fun consumed(link: String) {
        pending.compareAndSet(link, null)
    }

    private const val MAX_LINK = 4_096
}
