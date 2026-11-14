// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion-desktop/.../Main.kt; see NOTICE.
package run.ostrom.app.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.russhwolf.settings.Settings
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.Taskbar
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import run.ostrom.app.App
import run.ostrom.app.di.initKoin
import run.ostrom.app.presentation.DesktopLabel
import run.ostrom.app.presentation.desktopLabel
import run.ostrom.app.presentation.navigation.AppCommand
import run.ostrom.app.presentation.navigation.AppCommands
import run.ostrom.app.presentation.screens.MainTab
import run.ostrom.app.presentation.tabLabel

/**
 * The desktop app. Closing the window keeps it in the menu bar (the notification area on Windows and Linux), where
 * Open brings it back and Quit ends it; without a menu bar icon (a Linux desktop without a tray), closing the window
 * quits. The CLI's account follows the `ostrom` CLI of this computer while the app runs.
 */
fun main() {
    val environment = DesktopEnvironment.detect()
    val koin = initKoin(desktopModule(environment)).koin
    koin.getOrNull<CliAccountSync>()?.start(koin.get<CoroutineScope>())
    val resources = Thread.currentThread().contextClassLoader
    // The app's icon (the phones' one) for the window, the Dock, the taskbar and the menu bar.
    val image = checkNotNull(resources.getResourceAsStream(ICON)?.use(ImageIO::read)) { "$ICON is missing" }
    val icon = image.toPainter()
    // The packaged app takes its Dock icon from its bundle; run from Gradle, the Dock would show Java's.
    try {
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().iconImage = image
        }
    } catch (_: Exception) {
        // not coroutine-reachable: a cosmetic call before the app starts.
    }
    val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    /** Cmd on macOS, Ctrl elsewhere. */
    fun shortcut(key: Key) = if (mac) KeyShortcut(key, meta = true) else KeyShortcut(key, ctrl = true)
    val tray = try { SystemTray.isSupported() } catch (_: Throwable) { false }
    // The window opens where it was left, at the size it had: checked against the screens connected now.
    val placement = WindowPlacementStore(koin.get<Settings>())
    val screens = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
    } catch (_: Throwable) {
        emptyList()
    }
    val saved = placement.load(screens)
    application {
        var visible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            // macOS: a click on the Dock icon brings the hidden window back.
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { visible = true })
            }
        }
        if (tray) {
            Tray(icon = icon, tooltip = "Ostrom", onAction = { visible = true }, menu = {
                Item(desktopLabel(DesktopLabel.TRAY_OPEN), onClick = { visible = true })
                Separator()
                Item(desktopLabel(DesktopLabel.TRAY_QUIT), onClick = ::exitApplication)
            })
        }
        val windowState = rememberWindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = saved?.let { s -> if (s.x != null && s.y != null) WindowPosition(s.x.dp, s.y.dp) else null } ?: WindowPosition(Alignment.Center),
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(DEFAULT_WIDTH.dp, DEFAULT_HEIGHT.dp),
        )
        // The window not saved yet, written at once when the app quits before it rests.
        val unsaved = remember { arrayOfNulls<SavedWindow>(1) }
        LaunchedEffect(windowState) {
            // Once the window rests: a drag or a resize emits every frame, and a switch to maximized can pass through a
            // floating window at the maximized size. Fullscreen is not kept: the next launch opens the window it came from.
            snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.collectLatest { (mode, position, size) ->
                if (mode == WindowPlacement.Fullscreen) return@collectLatest
                val floating = mode == WindowPlacement.Floating
                val window = SavedWindow(
                    x = position.takeIf { floating && it.isSpecified }?.x?.value?.roundToInt(),
                    y = position.takeIf { floating && it.isSpecified }?.y?.value?.roundToInt(),
                    width = size.width.value.roundToInt(), height = size.height.value.roundToInt(), maximized = mode == WindowPlacement.Maximized,
                )
                unsaved[0] = window
                delay(SAVE_DELAY_MILLIS)
                placement.save(window)
                unsaved[0] = null
            }
        }
        DisposableEffect(Unit) { onDispose { unsaved[0]?.let(placement::save) } }
        Window(
            onCloseRequest = { if (tray) visible = false else exitApplication() },
            visible = visible,
            title = "Ostrom",
            icon = icon,
            state = windowState,
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(WindowPlacementStore.MIN_WIDTH, WindowPlacementStore.MIN_HEIGHT) }
            MenuBar {
                Menu(desktopLabel(DesktopLabel.MENU_FILE)) {
                    Item(desktopLabel(DesktopLabel.MENU_ADD_ACCOUNT), shortcut = shortcut(Key.N), onClick = { AppCommands.send(AppCommand.AddAccount) })
                    Separator()
                    Item(desktopLabel(DesktopLabel.MENU_CLOSE_WINDOW), shortcut = shortcut(Key.W), onClick = { if (tray) visible = false else exitApplication() })
                }
                Menu(desktopLabel(DesktopLabel.MENU_VIEW)) {
                    // Cmd+1 to Cmd+8 for the tabs, in their order; a tab the server does not serve changes nothing.
                    MainTab.entries.filter { it != MainTab.SETTINGS }.forEachIndexed { index, tab ->
                        Item(tabLabel(tab), shortcut = TAB_KEYS.getOrNull(index)?.let(::shortcut), onClick = { AppCommands.send(AppCommand.ShowTab(tab)) })
                    }
                    Separator()
                    Item(tabLabel(MainTab.SETTINGS), shortcut = shortcut(Key.Comma), onClick = { AppCommands.send(AppCommand.ShowTab(MainTab.SETTINGS)) })
                }
            }
            App()
        }
    }
}

private const val ICON = "ostrom-icon.png"
private const val DEFAULT_WIDTH = 1100
private const val DEFAULT_HEIGHT = 760
private const val SAVE_DELAY_MILLIS = 500L
private val TAB_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
