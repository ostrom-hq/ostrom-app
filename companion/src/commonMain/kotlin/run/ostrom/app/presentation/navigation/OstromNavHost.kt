// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/navigation/TeamCodeNavHost.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.domain.usecases.links.ArtifactLinks
import run.ostrom.app.domain.usecases.push.NotificationTarget
import run.ostrom.app.presentation.components.AppButton
import run.ostrom.app.presentation.components.AppSheet
import run.ostrom.app.presentation.components.Banner
import run.ostrom.app.presentation.components.ButtonKind
import run.ostrom.app.presentation.components.LocalWideLayout
import run.ostrom.app.presentation.components.SheetHeader
import run.ostrom.app.presentation.components.SheetViewModels
import run.ostrom.app.presentation.components.TransferButton
import run.ostrom.app.presentation.components.Tone
import run.ostrom.app.presentation.screens.AddServerScreen
import run.ostrom.app.presentation.screens.ArtifactScreen
import run.ostrom.app.presentation.screens.CloudSessionScreen
import run.ostrom.app.presentation.screens.TransferSheet
import run.ostrom.app.presentation.screens.TransfersScreen
import run.ostrom.app.presentation.screens.LoginScreen
import run.ostrom.app.presentation.screens.MainScreen
import run.ostrom.app.presentation.screens.MainTab
import run.ostrom.app.presentation.screens.GitHubLinkScreen
import run.ostrom.app.presentation.screens.MemberEnvironmentScreen
import run.ostrom.app.presentation.screens.MoreTabScreen
import run.ostrom.app.presentation.screens.NewCloudSessionScreen
import run.ostrom.app.presentation.screens.NotificationPreferencesScreen
import run.ostrom.app.presentation.screens.PersonalMcpServersScreen
import run.ostrom.app.presentation.screens.ReceiptScreen
import run.ostrom.app.presentation.screens.RequestScreen
import run.ostrom.app.presentation.screens.SessionScreen
import run.ostrom.app.presentation.screens.WorkflowEditScreen
import run.ostrom.app.presentation.screens.WorkflowRunScreen
import run.ostrom.app.presentation.screens.WorkflowScreen
import run.ostrom.app.presentation.screens.WorkflowStartScreen
import run.ostrom.app.presentation.viewmodels.ArtifactLinkOutcome
import run.ostrom.app.presentation.viewmodels.ArtifactViewModel
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.TransferViewModel
import run.ostrom.app.presentation.viewmodels.TransfersViewModel
import run.ostrom.app.presentation.viewmodels.Failure
import run.ostrom.app.presentation.viewmodels.GitHubLinkViewModel
import run.ostrom.app.presentation.viewmodels.MemberEnvironmentViewModel
import run.ostrom.app.presentation.viewmodels.NewCloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.NotificationPreferencesViewModel
import run.ostrom.app.presentation.viewmodels.NotificationSettingsViewModel
import run.ostrom.app.presentation.viewmodels.PersonalMcpServersViewModel
import run.ostrom.app.presentation.viewmodels.ReceiptViewModel
import run.ostrom.app.presentation.viewmodels.RequestViewModel
import run.ostrom.app.presentation.viewmodels.RootViewModel
import run.ostrom.app.presentation.viewmodels.SessionViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowEditViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowRunViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowRunsViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowStartViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowViewModel
import run.ostrom.app.presentation.viewmodels.words
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.action_close
import run.ostrom.app.resources.link_refused_title

/**
 * Before any account, a stack without tabs: add a server, sign in. Then the tabs ([MainRoute]), with the sign-in
 * screens pushed above them to add another account or sign one in again.
 */
@Composable
fun OstromNavHost(rootViewModel: RootViewModel = koinViewModel(), unlock: PhoneUnlock = koinInject(), features: FeaturesRepository = koinInject()) {
    // The app went to the background: a fresh unlock was for the person who left it (never on a pause, which Face ID's
    // sheet or the biometric prompt cause in the middle of an unlock).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { unlock.onBackground() }
    val hasAccounts by rootViewModel.hasAccounts.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    // A tab a tapped notification, or a pushed screen, asks the tabs to show.
    var requestedTab by remember { mutableStateOf<MainTab?>(null) }
    fun opensSessions(accountId: String) = features.opensSessions(accountId)
    suspend fun servesSessions(accountId: String) = features.servesSessions(accountId)
    // Captured once: the first sign-in navigates explicitly instead of rebuilding the graph.
    val startDestination: Any = remember { if (rootViewModel.hasAccounts.value) MainRoute else AddServerRoute }
    // The last account signed out (or the demo ended): back to the first screen, with nothing to go back to.
    LaunchedEffect(hasAccounts) {
        if (!hasAccounts && navController.currentBackStackEntry != null) {
            navController.navigate(AddServerRoute) { popUpTo(0) { inclusive = true } }
        } else if (hasAccounts && navController.currentBackStackEntry?.destination?.hasRoute<AddServerRoute>() == true && navController.previousBackStackEntry == null) {
            // The demo started from the first screen: its account is there, the tabs open.
            navController.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
        }
    }
    // A tapped notification: its request above the tabs of its account, its session (its agent waits, its turn failed)
    // when the server serves sessions, or else its waits, where that wait is.
    val incoming by IncomingLinks.link.collectAsStateWithLifecycle()
    // An artifact link (`ostrom://open?url=`): refused (said why, nothing opened) unless it is a signed-in account's.
    var refusedLink by remember { mutableStateOf<Failure?>(null) }
    LaunchedEffect(incoming, hasAccounts) {
        val link = incoming ?: return@LaunchedEffect
        if (ArtifactLinks.isOpenLink(link)) {
            IncomingLinks.consumed(link)
            when (val outcome = rootViewModel.openArtifactLink(link)) {
                is ArtifactLinkOutcome.Open -> {
                    val target = outcome.target
                    // Above what is shown, as TeamCode opens its links: a form or a sign-in in progress is kept, and back returns to it.
                    navController.navigate(ArtifactRoute(target.accountId, target.artifactId, target.version))
                }
                is ArtifactLinkOutcome.Refused -> refusedLink = outcome.failure
            }
            return@LaunchedEffect
        }
        if (!hasAccounts) return@LaunchedEffect
        IncomingLinks.consumed(link)
        when (val target = rootViewModel.openNotification(link)) {
            is NotificationTarget.Request -> navController.navigate(RequestRoute(target.accountId, target.approvalId))
            is NotificationTarget.Session -> {
                navController.popBackStack(MainRoute, inclusive = false)
                if (servesSessions(target.accountId)) {
                    navController.navigate(SessionRoute(target.accountId, target.sessionId))
                } else {
                    requestedTab = MainTab.WAITING
                }
            }
            is NotificationTarget.Waits -> {
                navController.popBackStack(MainRoute, inclusive = false)
                requestedTab = MainTab.WAITING
            }
            is NotificationTarget.Home, null -> Unit
        }
    }
    // The desktop's menu bar: a tab above whatever was opened over the tabs, or a new account.
    val signedIn by rememberUpdatedState(hasAccounts)
    LaunchedEffect(navController) {
        AppCommands.commands.collect { command ->
            when (command) {
                is AppCommand.ShowTab -> if (signedIn) {
                    navController.popBackStack(MainRoute, inclusive = false)
                    requestedTab = command.tab
                }
                AppCommand.AddAccount -> if (navController.currentBackStackEntry?.destination?.hasRoute<AddServerRoute>() != true) {
                    navController.navigate(AddServerRoute)
                }
            }
        }
    }
    refusedLink?.let { failure -> RefusedLinkSheet(failure, onDismiss = { refusedLink = null }) }
    NavHost(navController, startDestination = startDestination) {
        composable<AddServerRoute> {
            AddServerScreen(
                canGoBack = navController.previousBackStackEntry != null,
                onBack = { navController.popBackStack() },
                onContinue = { origin -> navController.navigate(LoginRoute(origin)) },
            )
        }
        composable<LoginRoute> { entry ->
            LoginScreen(
                route = entry.toRoute(),
                onBack = { navController.popBackStack() },
                onSignedIn = { navController.navigate(MainRoute) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<MainRoute> {
            MainScreen(
                onAddAccount = { navController.navigate(AddServerRoute) },
                onSignInAgain = { account -> navController.navigate(LoginRoute(account.origin, account.id)) },
                onOpenRequest = { accountId, id -> navController.navigate(RequestRoute(accountId, id)) },
                onOpenNotifications = { accountId -> navController.navigate(NotificationPreferencesRoute(accountId)) },
                onOpenEnvironment = { accountId -> navController.navigate(MemberEnvironmentRoute(accountId)) },
                onOpenMcpServers = { accountId -> navController.navigate(PersonalMcpServersRoute(accountId)) },
                onOpenGitHub = { accountId -> navController.navigate(GitHubLinkRoute(accountId)) },
                onOpenArtifact = { accountId, artifactId -> navController.navigate(ArtifactRoute(accountId, artifactId)) },
                onOpenSession = { accountId, sessionId -> navController.navigate(SessionRoute(accountId, sessionId)) },
                onOpenCloudSession = { accountId, sessionId -> navController.navigate(CloudSessionRoute(accountId, sessionId)) },
                onNewCloudSession = { accountId -> navController.navigate(NewCloudSessionRoute(accountId)) },
                onOpenTransfers = { accountId -> navController.navigate(TransfersRoute(accountId)) },
                onOpenWorkflow = { accountId, workflowId -> navController.navigate(WorkflowRoute(accountId, workflowId)) },
                onNewWorkflow = { accountId -> navController.navigate(WorkflowEditRoute(accountId)) },
                onOpenRun = { accountId, runId -> navController.navigate(WorkflowRunRoute(accountId, runId)) },
                onOpenTab = { tab, opensRequests ->
                    // Above the tabs only: one tab screen at a time, the one asked replacing another.
                    navController.navigate(MoreTabRoute(tab.name, opensRequests)) { popUpTo<MainRoute>() }
                },
                requestedTab = requestedTab,
                onTabRequestShown = { requestedTab = null },
            )
        }
        composable<MoreTabRoute> { entry ->
            val route = entry.toRoute<MoreTabRoute>()
            val tab = MainTab.entries.firstOrNull { it.name == route.tab }
            // The window widened: the tab returns to the rail, where it is a tab.
            val wide = LocalWideLayout.current
            if (tab == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else if (wide) {
                LaunchedEffect(Unit) {
                    navController.popBackStack(MainRoute, inclusive = false)
                    requestedTab = tab
                }
            } else {
                MoreTabScreen(
                    tab, route.opensRequests, onBack = { navController.popBackStack() },
                    onOpenRequest = { accountId, id -> navController.navigate(RequestRoute(accountId, id)) },
                    onOpenArtifact = { accountId, artifactId -> navController.navigate(ArtifactRoute(accountId, artifactId)) },
                )
            }
        }
        composable<SessionRoute> { entry ->
            val route = entry.toRoute<SessionRoute>()
            val viewModel: SessionViewModel = koinViewModel { parametersOf(route) }
            SessionScreen(
                viewModel,
                onBack = { navController.popBackStack() },
                onOpenReceipt = { receiptId -> navController.navigate(ReceiptRoute(route.accountId, receiptId)) },
                onOpenRun = { runId -> navController.openOnce(WorkflowRunRoute(route.accountId, runId)) },
            )
        }
        composable<ReceiptRoute> { entry ->
            val route = entry.toRoute<ReceiptRoute>()
            val viewModel: ReceiptViewModel = koinViewModel { parametersOf(route) }
            ReceiptScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<WorkflowRoute> { entry ->
            val route = entry.toRoute<WorkflowRoute>()
            val viewModel: WorkflowViewModel = koinViewModel { parametersOf(route) }
            val runs: WorkflowRunsViewModel = koinViewModel(key = "runs-${route.accountId}-${route.workflowId}") { parametersOf(route) }
            WorkflowScreen(
                viewModel, runs, onBack = { navController.popBackStack() }, onOpenRun = { runId -> navController.openOnce(WorkflowRunRoute(route.accountId, runId)) },
                onStart = { navController.navigate(WorkflowStartRoute(route.accountId, route.workflowId)) },
                onEdit = { navController.navigate(WorkflowEditRoute(route.accountId, route.workflowId)) },
                onDeleted = { navController.popBackStack() },
            )
        }
        composable<WorkflowEditRoute> { entry ->
            val route = entry.toRoute<WorkflowEditRoute>()
            val viewModel: WorkflowEditViewModel = koinViewModel { parametersOf(route) }
            WorkflowEditScreen(viewModel, onBack = { navController.popBackStack() }, onSaved = { workflowId ->
                // The saved workflow replaces the editor, and the page it was opened from when it was an edit: back returns to the list.
                navController.navigate(WorkflowRoute(route.accountId, workflowId)) {
                    if (route.workflowId != null) popUpTo<WorkflowRoute> { inclusive = true } else popUpTo<WorkflowEditRoute> { inclusive = true }
                }
            })
        }
        composable<WorkflowStartRoute> { entry ->
            val route = entry.toRoute<WorkflowStartRoute>()
            val viewModel: WorkflowStartViewModel = koinViewModel { parametersOf(route) }
            WorkflowStartScreen(viewModel, onBack = { navController.popBackStack() }, onStarted = { run ->
                // The new run replaces the form: back from it returns to the workflow.
                navController.navigate(WorkflowRunRoute(route.accountId, run.id)) { popUpTo<WorkflowStartRoute> { inclusive = true } }
            })
        }
        composable<WorkflowRunRoute> { entry ->
            val route = entry.toRoute<WorkflowRunRoute>()
            val viewModel: WorkflowRunViewModel = koinViewModel { parametersOf(route) }
            WorkflowRunScreen(
                viewModel,
                onBack = { navController.popBackStack() },
                onOpenSession = if (opensSessions(route.accountId)) ({ sessionId -> navController.openOnce(SessionRoute(route.accountId, sessionId)) }) else null,
                onOpenRun = { runId -> navController.openOnce(WorkflowRunRoute(route.accountId, runId)) },
            )
        }
        composable<CloudSessionRoute> { entry ->
            val route = entry.toRoute<CloudSessionRoute>()
            val viewModel: CloudSessionViewModel = koinViewModel { parametersOf(route) }
            var transferring by remember { mutableStateOf<TransferRoute?>(null) }
            CloudSessionScreen(viewModel, onBack = { navController.popBackStack() }, extraActions = { session ->
                TransferButton(route.accountId, session) { transferRouteOf(route.accountId, session)?.let { transferring = it } }
            })
            transferring?.let { transfer ->
                SheetViewModels {
                    val sheet: TransferViewModel = koinViewModel { parametersOf(transfer) }
                    TransferSheet(sheet, onDismiss = { transferring = null })
                }
            }
        }
        composable<TransfersRoute> { entry ->
            val route = entry.toRoute<TransfersRoute>()
            val viewModel: TransfersViewModel = koinViewModel { parametersOf(route) }
            TransfersScreen(viewModel, onBack = { navController.popBackStack() }, onOpenSession = { navController.navigate(CloudSessionRoute(route.accountId, it)) })
        }
        composable<NewCloudSessionRoute> { entry ->
            val route = entry.toRoute<NewCloudSessionRoute>()
            val viewModel: NewCloudSessionViewModel = koinViewModel { parametersOf(route) }
            NewCloudSessionScreen(viewModel, onBack = { navController.popBackStack() }, onCreated = { session ->
                // The new session replaces the form: back from it returns to the list.
                navController.navigate(CloudSessionRoute(route.accountId, session.id)) { popUpTo<NewCloudSessionRoute> { inclusive = true } }
            })
        }
        composable<NotificationPreferencesRoute> { entry ->
            val route = entry.toRoute<NotificationPreferencesRoute>()
            val viewModel: NotificationPreferencesViewModel = koinViewModel { parametersOf(route) }
            val settings: NotificationSettingsViewModel = koinViewModel { parametersOf(route) }
            NotificationPreferencesScreen(viewModel, settings, onBack = { navController.popBackStack() })
        }
        composable<MemberEnvironmentRoute> { entry ->
            val viewModel: MemberEnvironmentViewModel = koinViewModel { parametersOf(entry.toRoute<MemberEnvironmentRoute>()) }
            MemberEnvironmentScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<GitHubLinkRoute> { entry ->
            val viewModel: GitHubLinkViewModel = koinViewModel { parametersOf(entry.toRoute<GitHubLinkRoute>()) }
            GitHubLinkScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<PersonalMcpServersRoute> { entry ->
            val viewModel: PersonalMcpServersViewModel = koinViewModel { parametersOf(entry.toRoute<PersonalMcpServersRoute>()) }
            PersonalMcpServersScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<ArtifactRoute> { entry ->
            val route = entry.toRoute<ArtifactRoute>()
            val viewModel: ArtifactViewModel = koinViewModel { parametersOf(route) }
            ArtifactScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<RequestRoute> { entry ->
            val route = entry.toRoute<RequestRoute>()
            val viewModel: RequestViewModel = koinViewModel { parametersOf(route) }
            RequestScreen(viewModel, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Opens [route] (a session's record, a run) without stacking a second copy of it: when it is already on the stack (the
 * run a session was opened from, the parent a child run was opened from), back to it, what was above it dropped;
 * otherwise pushed. Going back and forth between a run and its session keeps one screen of each, and back then
 * returns to where the first of them was opened from.
 */
internal inline fun <reified T : Any> NavController.openOnce(route: T) {
    // Already the screen shown (a run that names itself): nothing to open.
    val top = currentBackStackEntry
    if (top != null && top.destination.hasRoute<T>() && top.toRoute<T>() == route) return
    if (!popBackStack(route, inclusive = false)) navigate(route)
}

/** Why a link was not opened, until the person closes it. */
@Composable
private fun RefusedLinkSheet(failure: Failure, onDismiss: () -> Unit) {
    AppSheet(onDismiss = onDismiss, modifier = Modifier.testTag("link-refused")) {
        SheetHeader(stringResource(Res.string.link_refused_title), trailing = {
            AppButton(stringResource(Res.string.action_close), onClick = onDismiss, kind = ButtonKind.GHOST, small = true, fill = false, modifier = Modifier.testTag("link-refused-close"))
        })
        Banner(failure.words(), Tone.WARN, Modifier.padding(16.dp))
    }
}
