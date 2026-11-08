package com.taskmind.ai.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.ProfileRepository
import com.taskmind.ai.ui.theme.LocalTm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val profile: ProfileRepository,
) : ViewModel() {
    fun finish(name: String, manager: String) {
        viewModelScope.launch {
            profile.update { it.copy(displayName = name.trim(), managerName = manager.trim()) }
            settings.setOnboardingDone()
        }
    }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    var name by rememberSaveable { mutableStateOf("") }
    var manager by rememberSaveable { mutableStateOf("") }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.finish(name, manager) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(LocalTm.current.heroGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("TaskMind AI", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your personal secretary. Never forget anything someone asks you to do.",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        Feature(Icons.Outlined.AutoAwesome, "Type or say it naturally", "“Remind me to call the doctor next Wednesday at 11 AM” becomes a dated, prioritised task.")
        Feature(Icons.Outlined.PhoneInTalk, "Turn conversations into tasks", "Capture calls you choose, import dialer recordings or paste notes — review every task before it's added.")
        Feature(Icons.Outlined.NotificationsActive, "Reminded at the right time", "Deadline reminders, morning briefing and an evening summary of what's left.")
        Feature(Icons.Outlined.Lock, "Private by default", "Runs fully on-device. Cloud AI is optional, uses your own key, and only receives what you allow.")

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("What should I call you?") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("onboarding_name"), shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = manager, onValueChange = { manager = it }, label = { Text("Your manager's name (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.finish(name, manager)
            },
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("onboarding_start"),
            shape = RoundedCornerShape(18.dp),
        ) { Text("Get started") }
        Spacer(Modifier.height(10.dp))
        Text(
            "Next, TaskMind asks to send notifications so reminders can reach you. Call capture can be turned on later in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Start) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
