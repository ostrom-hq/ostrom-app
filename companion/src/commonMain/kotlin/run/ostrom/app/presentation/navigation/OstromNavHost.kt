// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion presentation/navigation/TeamCodeNavHost.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import run.ostrom.app.domain.usecases.approvals.PhoneUnlock
import run.ostrom.app.presentation.screens.AddServerScreen
import run.ostrom.app.presentation.screens.LoginScreen
import run.ostrom.app.presentation.screens.MainScreen
import run.ostrom.app.presentation.screens.RequestScreen
import run.ostrom.app.presentation.viewmodels.RequestViewModel
import run.ostrom.app.presentation.viewmodels.RootViewModel

/**
 * Before any account, a stack without tabs: add a server, sign in. Then the tabs ([MainRoute]), with the sign-in
 * screens pushed above them to add another account or sign one in again.
 */
@Composable
fun OstromNavHost(rootViewModel: RootViewModel = koinViewModel(), unlock: PhoneUnlock = koinInject()) {
    // The app went to the background: a fresh unlock was for the person who left it (never on a pause, which Face ID's
    // sheet or the biometric prompt cause in the middle of an unlock).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { unlock.onBackground() }
    val hasAccounts by rootViewModel.hasAccounts.collectAsStateWithLifecycle()
    val navController = rememberNavController()
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
            )
        }
        composable<RequestRoute> { entry ->
            val route = entry.toRoute<RequestRoute>()
            val viewModel: RequestViewModel = koinViewModel { parametersOf(route) }
            RequestScreen(viewModel, onBack = { navController.popBackStack() })
        }
    }
}
