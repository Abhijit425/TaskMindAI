@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmind.ai.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskmind.ai.call.CaptureService
import com.taskmind.ai.call.CaptureState
import com.taskmind.ai.call.CaptureStateHolder
import com.taskmind.ai.data.db.CaptureMode
import com.taskmind.ai.data.prefs.AppSettings
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.ui.assistant.AssistantScreen
import com.taskmind.ai.ui.calls.CallDetailScreen
import com.taskmind.ai.ui.calls.CallsScreen
import com.taskmind.ai.ui.components.RecordingDot
import com.taskmind.ai.ui.home.HomeScreen
import com.taskmind.ai.ui.onboarding.OnboardingScreen
import com.taskmind.ai.ui.settings.PermissionsScreen
import com.taskmind.ai.ui.settings.SettingsScreen
import com.taskmind.ai.ui.tasks.QuickAddSheet
import com.taskmind.ai.ui.tasks.TaskEditScreen
import com.taskmind.ai.ui.tasks.TasksScreen
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PendingDeepLink(val taskId: Long?, val sessionId: Long?, val route: String?)

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

object Routes {
    const val HOME = "home"
    const val TASKS = "tasks"
    const val ASSISTANT = "assistant"
    const val CALLS = "calls"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    fun task(id: Long) = "task/$id"
    fun call(id: Long) = "call/$id"
    fun assistant(q: String) = "assistant?q=${android.net.Uri.encode(q)}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.TASKS, "Tasks", Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
    Tab(Routes.ASSISTANT, "Assistant", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Tab(Routes.CALLS, "Calls", Icons.Outlined.Call, Icons.Filled.Call),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@HiltViewModel
class RootViewModel @Inject constructor(
    captureStateHolder: CaptureStateHolder,
    calls: CallRepository,
) : ViewModel() {
    val capture: StateFlow<CaptureState> = captureStateHolder.state
    val pendingReview: StateFlow<Int> = calls.observePendingActionCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun TaskMindRoot(settings: AppSettings, deepLink: PendingDeepLink?, onDeepLinkHandled: () -> Unit) {
    if (!settings.onboardingDone) {
        OnboardingScreen()
        return
    }
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val vm: RootViewModel = hiltViewModel()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val pendingReview by vm.pendingReview.collectAsStateWithLifecycle()
    var quickAdd by rememberSaveable { mutableStateOf(false) }
    var quickAddPrefill by rememberSaveable { mutableStateOf("") }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route.orEmpty()
    val showBar = TABS.any { currentRoute.startsWith(it.route) }

    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        delay(50)
        when {
            link.sessionId != null -> nav.navigate(Routes.call(link.sessionId))
            link.taskId != null -> nav.navigate(Routes.task(link.taskId))
            link.route == Routes.PERMISSIONS -> nav.navigate(Routes.PERMISSIONS)
            link.route != null -> nav.navigateTab(link.route)
        }
        onDeepLinkHandled()
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                AnimatedVisibility(showBar, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                        TABS.forEach { tab ->
                            val selected = currentRoute.startsWith(tab.route)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { nav.navigateTab(tab.route) },
                                modifier = Modifier.testTag("tab_${tab.route}"),
                                icon = {
                                    if (tab.route == Routes.CALLS && pendingReview > 0) {
                                        BadgedBox(badge = { Badge { Text(if (pendingReview > 9) "9+" else "$pendingReview") } }) {
                                            Icon(if (selected) tab.selectedIcon else tab.icon, tab.label)
                                        }
                                    } else {
                                        Icon(if (selected) tab.selectedIcon else tab.icon, tab.label)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenTask = { nav.navigate(Routes.task(it)) },
                            onOpenCall = { nav.navigate(Routes.call(it)) },
                            onQuickAdd = { prefill -> quickAddPrefill = prefill; quickAdd = true },
                            onNavigateTab = { nav.navigateTab(it) },
                            onAsk = { nav.navigate(Routes.assistant(it)) },
                        )
                    }
                    composable(Routes.TASKS) {
                        TasksScreen(
                            onOpenTask = { nav.navigate(Routes.task(it)) },
                            onQuickAdd = { quickAddPrefill = ""; quickAdd = true },
                        )
                    }
                    composable(
                        "${Routes.ASSISTANT}?q={q}",
                        arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { entry ->
                        AssistantScreen(initialQuestion = entry.arguments?.getString("q"), onOpenTask = { nav.navigate(Routes.task(it)) })
                    }
                    composable(Routes.CALLS) {
                        CallsScreen(onOpenCall = { nav.navigate(Routes.call(it)) }, onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) })
                    }
                    composable(Routes.PERMISSIONS) {
                        PermissionsScreen(onBack = { nav.popBackStack() })
                    }
                    composable("task/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        TaskEditScreen(onBack = { nav.popBackStack() }, onOpenCall = { nav.navigate(Routes.call(it)) }, onOpenTask = { nav.navigate(Routes.task(it)) })
                    }
                    composable("call/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        CallDetailScreen(onBack = { nav.popBackStack() }, onOpenTask = { nav.navigate(Routes.task(it)) })
                    }
                }

                CaptureIndicator(capture, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp)) {
                    nav.navigateTab(Routes.CALLS)
                }
            }
        }

        if (quickAdd) {
            QuickAddSheet(
                prefill = quickAddPrefill,
                onDismiss = { quickAdd = false },
                onOpenTask = { quickAdd = false; nav.navigate(Routes.task(it)) },
            )
        }
    }
}

/** Always-visible indicator while the microphone is in use. Tapping opens Call Insights. */
@Composable
private fun CaptureIndicator(state: CaptureState, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val visible = state is CaptureState.Active || state is CaptureState.Armed || state is CaptureState.Finishing
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
        val label = when (state) {
            is CaptureState.Active -> {
                val verb = if (state.mode == CaptureMode.LIVE_TRANSCRIPTION) "Transcribing" else "Recording"
                "$verb · since ${TimeUtil.formatTime(state.startedAt)}"
            }
            is CaptureState.Armed -> "Capture armed — waiting for call"
            is CaptureState.Finishing -> "Saving capture…"
            CaptureState.Idle -> ""
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1F1F2B))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingDot()
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            if (state is CaptureState.Active || state is CaptureState.Armed) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "Stop",
                    color = Color(0xFFFF8A8E),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { CaptureService.stop(context) }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
