package com.taskmind.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.notify.DeepLink
import com.taskmind.ai.ui.nav.PendingDeepLink
import com.taskmind.ai.ui.nav.TaskMindRoot
import com.taskmind.ai.ui.theme.TaskMindTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var deepLink by mutableStateOf<PendingDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink = parse(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            TaskMindTheme(themeMode = s?.themeMode, dynamicColor = s?.dynamicColor == true) {
                if (s != null) {
                    TaskMindRoot(
                        settings = s,
                        deepLink = deepLink,
                        onDeepLinkHandled = { deepLink = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = parse(intent)
    }

    private fun parse(intent: Intent?): PendingDeepLink? {
        intent ?: return null
        val task = intent.getLongExtra(DeepLink.EXTRA_TASK_ID, -1L).takeIf { it > 0 }
        val session = intent.getLongExtra(DeepLink.EXTRA_SESSION_ID, -1L).takeIf { it > 0 }
        val route = intent.getStringExtra(DeepLink.EXTRA_ROUTE)
        return if (task == null && session == null && route == null) null else PendingDeepLink(task, session, route)
    }
}
