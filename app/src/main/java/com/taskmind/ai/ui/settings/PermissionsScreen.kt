@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmind.ai.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ai.ui.components.InfoBanner
import com.taskmind.ai.ui.components.SettingSwitch
import com.taskmind.ai.ui.components.TmCard
import com.taskmind.ai.ui.components.Tone
import com.taskmind.ai.ui.theme.LocalTm

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.tryStart(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** One place to see and grant every permission, each requested only for the feature that needs it. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var showConsent by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.READ_PHONE_STATE] == true) vm.enableCallPrompts(true)
        tick++
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    // Re-read on every resume / result.
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    val notif = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    val mic = granted(context, Manifest.permission.RECORD_AUDIO)
    val phone = granted(context, Manifest.permission.READ_PHONE_STATE)
    val contacts = granted(context, Manifest.permission.READ_CONTACTS)
    val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    val roleManager = context.getSystemService(RoleManager::class.java)
    val screeningAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
    val screening = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
    val battery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoBanner(Icons.Outlined.Info, "TaskMind asks only for what a feature needs. Nothing here allows hidden recording or access to call audio.", Tone.INFO)

            PermissionRow(Icons.Outlined.Notifications, "Notifications", "Reminders, briefings, call prompts and the recording indicator.", notif, required = true) {
                if (Build.VERSION.SDK_INT >= 33) single.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PermissionRow(Icons.Outlined.Mic, "Microphone", "Voice commands, voice notes and user-started capture.", mic, required = false) {
                single.launch(Manifest.permission.RECORD_AUDIO)
            }
            PermissionRow(Icons.Outlined.Schedule, "Exact alarms", "Deadline reminders at the exact minute, even in battery saver.", exact, required = false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.tryStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }

            Text("CALL CAPTURE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            TmCard(contentPadding = 14.dp) {
                SettingSwitch(
                    "Call capture prompts",
                    if (phone) "Ask “Enable AI Task Capture?” when a call starts." else "Needs phone-state permission (call ringing / connected / ended — no call content).",
                    settings?.callPromptsEnabled == true && phone,
                    { on -> if (on) showConsent = true else vm.enableCallPrompts(false) },
                )
            }
            PermissionRow(Icons.Outlined.PhoneInTalk, "Phone state", "Detects when a call rings, connects and ends.", phone, required = false) {
                showConsent = true
            }
            PermissionRow(Icons.Outlined.Contacts, "Contacts (optional)", "Shows the caller's name in the capture prompt.", contacts, required = false) {
                single.launch(Manifest.permission.READ_CONTACTS)
            }
            if (screeningAvailable) {
                PermissionRow(
                    Icons.Outlined.Call, "Call screening role (optional)",
                    "Lets TaskMind see the incoming number for the prompt. TaskMind never blocks, silences or alters calls.",
                    screening, required = false,
                ) {
                    roleManager?.let { roleLauncher.launch(it.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) }
                }
            }

            Text("RELIABILITY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            PermissionRow(
                Icons.Outlined.BatterySaver, "Background activity",
                "Vivo and other phones may stop apps in the background. Set TaskMind's battery usage to “Unrestricted” / allow background activity.",
                battery, required = false,
            ) {
                context.tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    if (showConsent) {
        CallConsentDialog(onDismiss = { showConsent = false }) {
            showConsent = false
            val perms = buildList {
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            multi.launch(perms.toTypedArray())
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, why: String, granted: Boolean, required: Boolean, onGrant: () -> Unit) {
    val tm = LocalTm.current
    TmCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title + if (required) " · recommended" else "", style = MaterialTheme.typography.titleSmall)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) Icon(Icons.Filled.CheckCircle, "Granted", tint = tm.success, modifier = Modifier.size(26.dp))
            else FilledTonalButton(onClick = onGrant) { Text("Allow") }
        }
    }
}
