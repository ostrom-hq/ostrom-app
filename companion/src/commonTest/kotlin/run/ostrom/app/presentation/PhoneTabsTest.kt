// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import run.ostrom.app.presentation.screens.MainTab
import run.ostrom.app.presentation.screens.MainTab.ACTIVITY
import run.ostrom.app.presentation.screens.MainTab.ARTIFACTS
import run.ostrom.app.presentation.screens.MainTab.HISTORY
import run.ostrom.app.presentation.screens.MainTab.INBOX
import run.ostrom.app.presentation.screens.MainTab.SESSIONS
import run.ostrom.app.presentation.screens.MainTab.SETTINGS
import run.ostrom.app.presentation.screens.MainTab.SPENDING
import run.ostrom.app.presentation.screens.MainTab.WAITING
import run.ostrom.app.presentation.screens.MainTab.WORKFLOWS
import run.ostrom.app.presentation.screens.phoneMoreTabs
import run.ostrom.app.presentation.screens.phoneTabs
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneTabsTest {
    @Test
    fun everyTabServedKeepsTheFourFirstAndSettingsTheRestInSettings() {
        val all = MainTab.entries.toList()
        assertEquals(listOf(INBOX, WAITING, SESSIONS, WORKFLOWS, SETTINGS), phoneTabs(all))
        assertEquals(listOf(HISTORY, SPENDING, ACTIVITY, ARTIFACTS), phoneMoreTabs(all))
    }

    @Test
    fun tabsNotServedLeaveRoomForTheOthersInTheirOrder() {
        val served = listOf(SESSIONS, HISTORY, SPENDING, ACTIVITY, ARTIFACTS, WORKFLOWS, SETTINGS)
        assertEquals(listOf(SESSIONS, WORKFLOWS, HISTORY, SPENDING, SETTINGS), phoneTabs(served))
        assertEquals(listOf(ACTIVITY, ARTIFACTS), phoneMoreTabs(served))
        // Few tabs: all of them in the bar, nothing in Settings.
        assertEquals(listOf(ACTIVITY, SETTINGS), phoneTabs(listOf(ACTIVITY, SETTINGS)))
        assertEquals(emptyList(), phoneMoreTabs(listOf(ACTIVITY, SETTINGS)))
    }
}
