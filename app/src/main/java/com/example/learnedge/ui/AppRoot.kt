package com.example.learnedge.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.learnedge.appContainer
import com.example.learnedge.ui.diagnostic.DiagnosticScreen
import com.example.learnedge.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

object Routes {
    const val ONBOARDING = "onboarding"
    const val DIAGNOSTIC = "diagnostic"
    const val MAIN = "main"
}

/** Picks the first screen once at launch: onboarding, then the diagnostic quiz, then the main tabs. */
class RootViewModel(app: Application) : AndroidViewModel(app) {
    val startRoute: StateFlow<String?> = flow {
        val profile = app.appContainer.profiles.get()
        emit(
            when {
                profile == null -> Routes.ONBOARDING
                !profile.diagnosticCompleted -> Routes.DIAGNOSTIC
                else -> Routes.MAIN
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun AppRoot(rootViewModel: RootViewModel = viewModel()) {
    val startRoute by rootViewModel.startRoute.collectAsStateWithLifecycle()
    val start = startRoute
    if (start == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val navController = rememberNavController()
    NavHost(navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            val canCancel = navController.previousBackStackEntry != null
            OnboardingScreen(
                onSaved = { needsDiagnostic ->
                    navController.replaceAll(if (needsDiagnostic) Routes.DIAGNOSTIC else Routes.MAIN)
                },
                onCancel = if (canCancel) ({ navController.popBackStack() }) else null,
            )
        }
        composable(Routes.DIAGNOSTIC) {
            DiagnosticScreen(onFinished = { navController.replaceAll(Routes.MAIN) })
        }
        composable(Routes.MAIN) {
            MainScreen(onEditProfile = { navController.navigate(Routes.ONBOARDING) })
        }
    }
}

/** Navigates to [route] and clears the back stack, so Back doesn't return to onboarding or the quiz. */
private fun NavHostController.replaceAll(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
