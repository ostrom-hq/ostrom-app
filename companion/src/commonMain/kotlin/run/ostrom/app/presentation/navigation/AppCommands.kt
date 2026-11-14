// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/navigation/AppCommands.kt; see NOTICE.
package run.ostrom.app.presentation.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import run.ostrom.app.presentation.screens.MainTab

/** What the desktop's menu bar asks of the app. */
sealed interface AppCommand {
    /** Shows [tab] of the signed-in app, above anything opened over the tabs; the first tab when its server lacks it. */
    data class ShowTab(val tab: MainTab) : AppCommand

    /** Opens the screen that adds an account. */
    data object AddAccount : AppCommand
}

/** Commands from outside the composition: the desktop's native menu bar sends them with their shortcuts. */
object AppCommands {
    private val flow = MutableSharedFlow<AppCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<AppCommand> = flow.asSharedFlow()

    fun send(command: AppCommand) {
        flow.tryEmit(command)
    }
}
