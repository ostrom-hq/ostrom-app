// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import run.ostrom.app.domain.models.ActionKind
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationToggle
import run.ostrom.app.domain.models.PushTestStatus
import run.ostrom.app.domain.models.ReceiptRejection
import run.ostrom.app.domain.models.ReceiptSummary
import run.ostrom.app.domain.models.RunOrigin
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.SessionHost
import run.ostrom.app.domain.models.SessionTodo
import run.ostrom.app.domain.models.TodoItem
import run.ostrom.app.domain.models.TodoItemState
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.services.CliUpdateException
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.components.WindowTitleBar
import run.ostrom.app.presentation.components.isImage
import run.ostrom.app.presentation.components.label
import run.ostrom.app.presentation.components.refusal
import run.ostrom.app.presentation.components.sentence
import run.ostrom.app.presentation.components.sizeLabel
import run.ostrom.app.presentation.components.systemOf
import run.ostrom.app.presentation.components.typesLabel
import run.ostrom.app.presentation.components.waitLabel
import run.ostrom.app.presentation.screens.MainTab
import run.ostrom.app.presentation.screens.PanelPlacement
import run.ostrom.app.presentation.screens.RunningWork
import run.ostrom.app.presentation.screens.SubagentLayout
import run.ostrom.app.presentation.screens.SubagentPanelMode
import run.ostrom.app.presentation.screens.approveLabel
import run.ostrom.app.presentation.screens.hint
import run.ostrom.app.presentation.screens.label
import run.ostrom.app.presentation.screens.originText
import run.ostrom.app.presentation.screens.phoneMoreTabs
import run.ostrom.app.presentation.screens.phoneTabs
import run.ostrom.app.presentation.screens.reasonText
import run.ostrom.app.presentation.screens.rowKeys
import run.ostrom.app.presentation.screens.rowTag
import run.ostrom.app.presentation.screens.runStatusTone
import run.ostrom.app.presentation.screens.subagentLayout
import run.ostrom.app.presentation.screens.subagentPanelMode
import run.ostrom.app.presentation.screens.tabs
import run.ostrom.app.presentation.screens.title
import run.ostrom.app.presentation.screens.todoCounter
import run.ostrom.app.presentation.screens.words
import run.ostrom.app.presentation.transcript.BackgroundCommand
import run.ostrom.app.presentation.transcript.LiveTasks
import run.ostrom.app.presentation.transcript.Subagent
import run.ostrom.app.presentation.transcript.SubagentState
import run.ostrom.app.presentation.viewmodels.HomeUiState
import kotlinx.serialization.json.buildJsonObject
import run.ostrom.app.domain.models.BackgroundTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure helpers of the screens (labels, layouts, keys): what the Compose files only call. */
class PresentationHelpersTest {
    private fun summary(id: String) = ReceiptSummary(id, "approved", false, "ada", "gate", "g1", 1_000L)
    private fun entry(id: String?) = HistoryEntry(buildJsonObject {}, id?.let(::summary))

    @Test
    fun everyKeyOfAHistoryRowIsUniqueAndKeepsTheReceiptId() {
        val rows = listOf(entry("r1"), entry(null), entry("r2"), entry("r1"), entry("r1"))
        assertEquals(listOf("receipt:r1", "unreadable:1", "receipt:r2", "receipt:r1#1", "receipt:r1#2"), rowKeys(rows))
        assertEquals("receipt-r1", rowTag(0, entry("r1")))
        assertEquals("receipt-unreadable-3", rowTag(3, entry(null)))
    }

    private fun <T> assertDistinct(values: Collection<T>, text: (T) -> org.jetbrains.compose.resources.StringResource) {
        assertEquals(values.size, values.map { text(it).key }.toSet().size, "every case has its own sentence")
    }

    @Test
    fun everyCaseHasItsOwnSentence() {
        assertDistinct(RunOrigin.entries, ::originText)
        assertDistinct(ReceiptRejection.entries, ::reasonText)
        assertDistinct(DecisionProblem.entries) { it.words() }
        assertDistinct(PushTestStatus.entries) { it.label() }
        assertDistinct(NotificationKind.entries) { it.title() }
        assertDistinct(NotificationKind.entries) { it.hint() }
        assertDistinct(NotificationToggle.entries) { it.label() }
        assertDistinct(TransferState.entries) { it.label() }
        assertDistinct(CliUpdateException.Reason.entries) { it.sentence() }
    }

    @Test
    fun theApproveButtonNamesWhatTheYesLetsThrough() {
        val labels = listOf(ActionKind.PUSH, ActionKind.MERGE, ActionKind.DEPLOY).map { approveLabel(it).key }
        assertEquals(3, labels.toSet().size)
        val plain = approveLabel(null).key
        assertFalse(plain in labels)
        assertEquals(plain, approveLabel(ActionKind.TOOL).key)
    }

    @Test
    fun aRefusalOfATransferHasASentenceOnlyWhenTheAppKnowsItsCode() {
        val known = listOf(
            "transfer_pending", "session_preparing", "session_not_running", "session_transferring", "transfer_in_progress", "session_busy",
            "transfer_disabled", "transfer_forbidden", "forbidden", "transfer_expired", "transfer_not_open", "idempotency_conflict",
            "invalid_transfer", "recipient_ineligible",
        )
        known.forEach { assertTrue(refusal(it) != null, it) }
        assertEquals(refusal("session_transferring")?.key, refusal("transfer_in_progress")?.key)
        assertEquals(refusal("transfer_forbidden")?.key, refusal("forbidden")?.key)
        assertNull(refusal("something_else"))
    }

    @Test
    fun sizesAreWordedInPowersOf1024() {
        assertEquals("0 B", sizeLabel(0))
        assertEquals("1023 B", sizeLabel(1023))
        assertEquals("1 KB", sizeLabel(1024))
        assertEquals("245 KB", sizeLabel(245 * 1024L))
        assertEquals("1.0 MB", sizeLabel(1024L * 1024))
        assertEquals("1.5 MB", sizeLabel(1_572_864))
    }

    @Test
    fun typesAndWaitsAreWordedForPeople() {
        assertEquals("PNG, PDF, text/x-unknown", typesLabel(listOf("image/png", "application/pdf", "text/x-unknown")))
        assertEquals("45 s", waitLabel(45))
        assertEquals("1 min", waitLabel(60))
        assertEquals("2 min", waitLabel(90))
        assertEquals("1 h", waitLabel(3600))
        assertEquals("2 h", waitLabel(5400))
    }

    @Test
    fun anImageIsKnownByItsExtension() {
        assertTrue(isImage("Photo.PNG"))
        assertTrue(isImage("a.b.jpeg"))
        assertFalse(isImage("notes.txt"))
        assertFalse(isImage("png"))
    }

    @Test
    fun aDeviceSystemIsWordedAsItsVendorDoes() {
        assertNull(systemOf(null, "15.1"))
        assertEquals("macOS 15.1", systemOf("macos", "15.1"))
        assertEquals("Windows 11", systemOf("windows", "11"))
        assertEquals("Linux", systemOf("linux", null))
        assertEquals("iOS", systemOf("ios", "  "))
        assertEquals("Android 15", systemOf("android", "15"))
        assertEquals("freebsd 14", systemOf("freebsd", "14"))
        assertEquals("macOS 15.1", systemOf(SessionHost("Ada's MacBook", "macos", "15.1", "app", online = true, removed = false, permissionMode = null)))
    }

    private fun session(name: String?, repo: String?) =
        CloudSession("s1", name, "claude", null, repo, null, CloudSessionStatus.RUNNING, null, null, null, null, 1L, 1L, null, null, null, null, null)

    @Test
    fun aSessionIsCalledByItsNameThenItsRepositoryThenItsAgent() {
        assertEquals("Mill", session("Mill", "https://github.com/acme/engine").title())
        assertEquals("engine", session("  ", "https://github.com/acme/engine").title())
        assertEquals("engine", session(null, "https://github.com/acme/engine").title())
        assertEquals("claude", session(null, null).title())
    }

    @Test
    fun theTodoCounterIgnoresDroppedItems() {
        fun item(id: String, state: TodoItemState) = TodoItem(id, id, "", "ok", state, addedLater = false, addedReason = null, proof = null, unverified = false, dropReason = null)
        val todo = SessionTodo("s1", "plan", "active", listOf(item("a", TodoItemState.DONE), item("b", TodoItemState.DOING), item("c", TodoItemState.DROPPED)), null)
        assertEquals("1 / 2", todoCounter(todo))
    }

    @Test
    fun aRunStatusHasATone() {
        assertEquals(Tone.ACCENT, runStatusTone(RunStatus.RUNNING))
        assertEquals(Tone.ACCENT, runStatusTone(RunStatus.QUEUED))
        assertEquals(Tone.WARN, runStatusTone(RunStatus.WAITING))
        assertEquals(Tone.DANGER, runStatusTone(RunStatus.FAILED))
        assertEquals(Tone.NEUTRAL, runStatusTone(RunStatus.SUCCEEDED))
        assertEquals(Tone.MUTED, runStatusTone(RunStatus.CANCELLED))
        assertEquals(Tone.MUTED, runStatusTone(RunStatus.UNKNOWN))
    }

    private fun home(vararg features: String) = HomeUiState(capabilities = ServerCapabilities("1.4.0", 1, features.toSet()))

    @Test
    fun theTabsAreThoseTheServerServes() {
        assertEquals(listOf(MainTab.ACTIVITY, MainTab.SETTINGS), HomeUiState().tabs())
        assertEquals(listOf(MainTab.INBOX, MainTab.ACTIVITY, MainTab.SETTINGS), home(Features.GATES).tabs())
        assertEquals(listOf(MainTab.SESSIONS, MainTab.ACTIVITY, MainTab.SETTINGS), home(Features.RUNNERS_CLOUD).tabs(), "cloud sessions alone show the Sessions tab")
        assertEquals(MainTab.entries, home(*MainTab.entries.mapNotNull { it.feature }.toTypedArray()).tabs())
        assertEquals(listOf(MainTab.ACTIVITY, MainTab.SETTINGS), home("unknown.v1").tabs())
    }

    @Test
    fun aPhoneKeepsSettingsLastAndTheRestInTheMoreList() {
        val all = MainTab.entries
        val bar = phoneTabs(all)
        assertEquals(MainTab.SETTINGS, bar.last())
        assertEquals(all.toSet(), (bar + phoneMoreTabs(all)).toSet())
        assertTrue(phoneMoreTabs(listOf(MainTab.INBOX, MainTab.SETTINGS)).isEmpty())
        assertEquals(listOf(MainTab.INBOX, MainTab.ACTIVITY), phoneTabs(listOf(MainTab.INBOX, MainTab.ACTIVITY)), "no Settings tab served: none added")
    }

    @Test
    fun theWindowSizeChoosesWhereThePanelGoes() {
        assertEquals(SubagentPanelMode.SCREEN, subagentPanelMode(400, 900), "a narrow window")
        assertEquals(SubagentPanelMode.SCREEN, subagentPanelMode(900, 400), "a wide but short one (a phone on its side)")
        assertEquals(SubagentPanelMode.OVERLAY, subagentPanelMode(900, 900))
        assertEquals(SubagentPanelMode.SIDE, subagentPanelMode(1300, 900))
        assertEquals(SubagentPanelMode.SIDE_AND_THREAD, subagentPanelMode(1700, 900))
    }

    @Test
    fun thePanelLayoutFollowsTheModeAndWhatTheMemberOpenedOrHid() {
        fun layout(mode: SubagentPanelMode, content: Boolean = true, open: Boolean = false, hidden: Boolean = false, thread: Boolean = false) =
            subagentLayout(mode, content, open, hidden, thread)
        assertEquals(SubagentLayout(PanelPlacement.NONE, threadBeside = false, threadReplacesMain = false), layout(SubagentPanelMode.SIDE, content = false, open = true))
        assertEquals(PanelPlacement.NONE, layout(SubagentPanelMode.SCREEN).panel)
        assertEquals(PanelPlacement.SCREEN, layout(SubagentPanelMode.SCREEN, open = true).panel)
        assertEquals(PanelPlacement.NONE, layout(SubagentPanelMode.OVERLAY).panel)
        assertEquals(PanelPlacement.OVERLAY, layout(SubagentPanelMode.OVERLAY, open = true).panel)
        assertEquals(PanelPlacement.SIDE, layout(SubagentPanelMode.SIDE).panel)
        assertEquals(PanelPlacement.NONE, layout(SubagentPanelMode.SIDE, hidden = true).panel)
        assertEquals(PanelPlacement.SIDE, layout(SubagentPanelMode.SIDE_AND_THREAD).panel)
        assertEquals(PanelPlacement.NONE, layout(SubagentPanelMode.SIDE_AND_THREAD, hidden = true).panel)
        // An open subagent: beside the main thread when the window is extra large, else in its place.
        assertEquals(SubagentLayout(PanelPlacement.SIDE, threadBeside = true, threadReplacesMain = false), layout(SubagentPanelMode.SIDE_AND_THREAD, thread = true))
        assertEquals(SubagentLayout(PanelPlacement.SIDE, threadBeside = false, threadReplacesMain = true), layout(SubagentPanelMode.SIDE, thread = true))
        assertEquals(SubagentLayout(PanelPlacement.SIDE, threadBeside = false, threadReplacesMain = false), layout(SubagentPanelMode.SIDE))
    }

    @Test
    fun theRunningWorkCountsWhatRunsNow() {
        fun agent(state: SubagentState) = Subagent("a", "t", "d", state, 0, 0, 0, null, 0, null)
        val task = BackgroundTask("t1", "local_bash", "build", null, null, null)
        val none = RunningWork(emptyList(), null, emptyList())
        assertFalse(none.hasContent)
        assertEquals(0, none.scheduled)
        assertEquals(0, none.running)
        val work = RunningWork(
            listOf(agent(SubagentState.RUNNING), agent(SubagentState.DONE)),
            LiveTasks(listOf(task), stoppable = true, scheduled = 2),
            listOf(BackgroundCommand(task, "make", monitor = false)),
        )
        assertTrue(work.hasContent)
        assertEquals(2, work.scheduled)
        assertEquals(4, work.running, "one active subagent, one command, two wake-ups")
        assertTrue(RunningWork(listOf(agent(SubagentState.DONE)), null, emptyList()).hasContent)
        assertTrue(RunningWork(emptyList(), null, listOf(BackgroundCommand(task, null, monitor = true))).hasContent)
        assertTrue(RunningWork(emptyList(), LiveTasks(emptyList(), stoppable = false, scheduled = 1), emptyList()).hasContent)
    }

    @Test
    fun theMacTitleBarIsTheTallerOneFromMacOs26() {
        assertEquals(WindowTitleBar.MAC, WindowTitleBar.forMac(null))
        assertEquals(WindowTitleBar.MAC, WindowTitleBar.forMac("15.6.1"))
        assertEquals(WindowTitleBar.MAC, WindowTitleBar.forMac("not a version"))
        assertEquals(WindowTitleBar.MAC_TAHOE, WindowTitleBar.forMac("26.0"))
        assertEquals(WindowTitleBar.MAC_TAHOE, WindowTitleBar.forMac(" 27 "))
        val none = WindowTitleBar()
        assertEquals(none, none.copy())
        assertEquals(none.hashCode(), WindowTitleBar().hashCode())
        assertTrue(none.toString().contains("height"))
        assertTrue(none != WindowTitleBar.MAC)
    }
}
