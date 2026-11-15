package com.example.learnedge.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.learnedge.ui.lesson.LessonScreen
import com.example.learnedge.ui.plan.PlanScreen
import com.example.learnedge.ui.progress.ProgressScreen
import com.example.learnedge.ui.quiz.QuizScreen
import com.example.learnedge.ui.today.TodayScreen
import com.example.learnedge.ui.tutor.ChatScreen
import com.example.learnedge.ui.tutor.ChatViewModel

/** A topic's lesson, opened from Today or Plan. It is not a tab, so no bottom-bar item is selected on it. */
private const val LESSON_ROUTE = "lesson/{topicId}"

/** A quiz on one topic, started from its lesson. Not a tab either. */
private const val QUIZ_ROUTE = "quiz/{topicId}"

/** Bottom-bar destinations. The icon is an emoji so no icon library is needed. */
enum class Tab(val route: String, val label: String, val icon: String) {
    Today("today", "Today", "📅"),
    Plan("plan", "Plan", "🗺️"),
    Tutor("tutor", "Tutor", "💬"),
    Progress("progress", "Progress", "📈"),
}

@Composable
fun MainScreen(onEditProfile: () -> Unit) {
    val navController = rememberNavController()
    // Scoped to the activity, not a destination, so the on-device model stays loaded across tabs and screens.
    val activity = LocalContext.current as ComponentActivity
    val chatViewModel: ChatViewModel = viewModel(viewModelStoreOwner = activity)
    val openTopic: (String) -> Unit = { topicId -> navController.navigate("lesson/$topicId") }

    Scaffold(bottomBar = { BottomBar(navController) }) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Today.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Tab.Today.route) { TodayScreen(onEditProfile, onOpenTopic = openTopic) }
            composable(Tab.Plan.route) { PlanScreen(onOpenTopic = openTopic) }
            composable(Tab.Tutor.route) { ChatScreen(chatViewModel) }
            composable(Tab.Progress.route) { ProgressScreen() }
            composable(LESSON_ROUTE) {
                LessonScreen(
                    onBack = { navController.popBackStack() },
                    onTakeQuiz = { topicId -> navController.navigate("quiz/$topicId") },
                )
            }
            composable(QUIZ_ROUTE) { QuizScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Text(tab.icon) },
                label = { Text(tab.label) },
            )
        }
    }
}
