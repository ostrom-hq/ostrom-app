// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion presentation/navigation/TeamCodeNavHost.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.domain.usecases.push.NotificationTarget
import run.ostrom.app.presentation.screens.AddServerScreen
import run.ostrom.app.presentation.screens.ArtifactScreen
import run.ostrom.app.presentation.screens.LoginScreen
import run.ostrom.app.presentation.screens.MainScreen
import run.ostrom.app.presentation.screens.MainTab
import run.ostrom.app.presentation.screens.MemberEnvironmentScreen
import run.ostrom.app.presentation.screens.NotificationPreferencesScreen
import run.ostrom.app.presentation.screens.PersonalMcpServersScreen
import run.ostrom.app.presentation.screens.RequestScreen
import run.ostrom.app.presentation.screens.SessionScreen
import run.ostrom.app.presentation.screens.WorkflowEditScreen
import run.ostrom.app.presentation.screens.WorkflowRunScreen
import run.ostrom.app.presentation.screens.WorkflowScreen
import run.ostrom.app.presentation.screens.WorkflowStartScreen
import run.ostrom.app.presentation.viewmodels.ArtifactViewModel
import run.ostrom.app.presentation.viewmodels.MemberEnvironmentViewModel
import run.ostrom.app.presentation.viewmodels.NotificationPreferencesViewModel
import run.ostrom.app.presentation.viewmodels.PersonalMcpServersViewModel
import run.ostrom.app.presentation.viewmodels.NotificationSettingsViewModel
import run.ostrom.app.presentation.viewmodels.RequestViewModel
import run.ostrom.app.presentation.viewmodels.RootViewModel
import run.ostrom.app.presentation.viewmodels.SessionViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowEditViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowRunViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowRunsViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowStartViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowViewModel

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
    fun opensSessions(accountId: String) = features.cached(accountId)?.features?.contains(Features.SESSIONS) == true
    // Read again when nothing was cached yet (a cold start from the notification); offline, what was cached decides.
    suspend fun servesSessions(accountId: String): Boolean = try {
        features.has(accountId, Features.SESSIONS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        opensSessions(accountId)
    }
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
    LaunchedEffect(incoming, hasAccounts) {
        val link = incoming ?: return@LaunchedEffect
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
                onOpenArtifact = { accountId, artifactId -> navController.navigate(ArtifactRoute(accountId, artifactId)) },
                onOpenSession = { accountId, sessionId -> navController.navigate(SessionRoute(accountId, sessionId)) },
                onOpenWorkflow = { accountId, workflowId -> navController.navigate(WorkflowRoute(accountId, workflowId)) },
                onNewWorkflow = { accountId -> navController.navigate(WorkflowEditRoute(accountId)) },
                onOpenRun = { accountId, runId -> navController.navigate(WorkflowRunRoute(accountId, runId)) },
                requestedTab = requestedTab,
                onTabRequestShown = { requestedTab = null },
            )
        }
        composable<SessionRoute> { entry ->
            val route = entry.toRoute<SessionRoute>()
            val viewModel: SessionViewModel = koinViewModel { parametersOf(route) }
            SessionScreen(
                viewModel,
                onBack = { navController.popBackStack() },
                onOpenHistory = { requestedTab = MainTab.HISTORY; navController.popBackStack(MainRoute, inclusive = false) },
                onOpenRun = { runId -> navController.navigate(WorkflowRunRoute(route.accountId, runId)) },
            )
        }
        composable<WorkflowRoute> { entry ->
            val route = entry.toRoute<WorkflowRoute>()
            val viewModel: WorkflowViewModel = koinViewModel { parametersOf(route) }
            val runs: WorkflowRunsViewModel = koinViewModel(key = "runs-${route.accountId}-${route.workflowId}") { parametersOf(route) }
            WorkflowScreen(
                viewModel, runs, onBack = { navController.popBackStack() }, onOpenRun = { runId -> navController.navigate(WorkflowRunRoute(route.accountId, runId)) },
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
                onOpenSession = if (opensSessions(route.accountId)) ({ sessionId -> navController.navigate(SessionRoute(route.accountId, sessionId)) }) else null,
                onOpenRun = { runId -> navController.navigate(WorkflowRunRoute(route.accountId, runId)) },
            )
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
