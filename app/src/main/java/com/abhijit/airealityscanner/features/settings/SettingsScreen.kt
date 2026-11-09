@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.abhijit.airealityscanner.features.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhijit.airealityscanner.BuildConfig
import com.abhijit.airealityscanner.core.voice.VoiceAvailability
import com.abhijit.airealityscanner.domain.model.AiProviderType
import com.abhijit.airealityscanner.domain.model.AppLanguage
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.model.ThemeMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    var showConsentInfo by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.update { it.copy(voiceEnabled = granted) }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    LaunchedEffect(settings.language) { viewModel.checkVoice() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------- AI provider
            item {
                SettingsCard("AI vision engine") {
                    AiProviderType.entries.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.update { it.copy(provider = p) } }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = settings.provider == p, onClick = { viewModel.update { it.copy(provider = p) } })
                            Column(Modifier.padding(top = 10.dp)) {
                                Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    when (settings.provider) {
                        AiProviderType.ANTHROPIC -> CloudProviderConfig(
                            provider = AiProviderType.ANTHROPIC,
                            hasKey = settings.hasAnthropicKey,
                            keyHint = "sk-ant-…",
                            model = settings.anthropicModel,
                            presets = AppSettings.ANTHROPIC_MODEL_PRESETS,
                            onModel = { m -> viewModel.update { it.copy(anthropicModel = m) } },
                            onSaveKey = { viewModel.saveApiKey(AiProviderType.ANTHROPIC, it) },
                            onClearKey = { viewModel.clearApiKey(AiProviderType.ANTHROPIC) },
                            keyUrl = "console.anthropic.com → API keys",
                        )
                        AiProviderType.OPENAI -> {
                            CloudProviderConfig(
                                provider = AiProviderType.OPENAI,
                                hasKey = settings.hasOpenAiKey,
                                keyHint = "sk-…",
                                model = settings.openAiModel,
                                presets = AppSettings.OPENAI_MODEL_PRESETS,
                                onModel = { m -> viewModel.update { it.copy(openAiModel = m) } },
                                onSaveKey = { viewModel.saveApiKey(AiProviderType.OPENAI, it) },
                                onClearKey = { viewModel.clearApiKey(AiProviderType.OPENAI) },
                                keyUrl = "platform.openai.com → API keys",
                            )
                            var baseUrl by remember(settings.openAiBaseUrl) { mutableStateOf(settings.openAiBaseUrl) }
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("API base URL (OpenAI-compatible)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (baseUrl != settings.openAiBaseUrl) TextButton(onClick = { viewModel.update { it.copy(openAiBaseUrl = baseUrl) } }) { Text("Save") }
                                },
                            )
                        }
                        AiProviderType.ON_DEVICE -> Text(
                            "On-device mode works offline and uploads nothing. It provides generic labels, object boxes, OCR and barcode reading, " +
                                "but cannot identify species, products or landmarks, and cannot answer follow-up questions.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (settings.provider.isCloud) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = viewModel::testConnection, enabled = !ui.testing && settings.hasKeyForProvider) {
                                Text("Test connection")
                            }
                            Spacer(Modifier.width(12.dp))
                            if (ui.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        ui.testResult?.let {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    if (ui.testOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (ui.testOk) Color(0xFF34C77B) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ---------------- Privacy
            item {
                SettingsCard("Privacy & data") {
                    SwitchRow(
                        title = "Allow cloud image analysis",
                        subtitle = if (settings.cloudConsentGiven) "Images you scan are sent to ${settings.provider.displayName} for analysis. Turn off to revoke."
                        else "Off — you'll be asked before any image is uploaded.",
                        checked = settings.cloudConsentGiven,
                        onChange = { on -> if (on) showConsentInfo = true else viewModel.update { it.copy(cloudConsentGiven = false) } },
                    )
                    SwitchRow(
                        title = "Save scans to history automatically",
                        subtitle = "When off, scans are kept for 24 hours unless you tap Save.",
                        checked = settings.autoSaveScans,
                        onChange = { on -> viewModel.update { it.copy(autoSaveScans = on) } },
                    )
                    Text("Auto-delete history after", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Never", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (days, label) ->
                            FilterChip(
                                selected = settings.autoDeleteDays == days,
                                onClick = { viewModel.update { it.copy(autoDeleteDays = days) } },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirm = "images" }) { Text("Delete all images") }
                        OutlinedButton(onClick = { confirm = "history" }) { Text("Delete all history") }
                    }
                    Text(
                        "Scans, images and conversations are stored only in this app's private storage and are excluded from backups. " +
                            "API keys are encrypted with the Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------------- Voice
            item {
                SettingsCard("Voice assistant") {
                    SwitchRow(
                        title = "Enable voice features",
                        subtitle = "Ask questions by voice. Requests microphone permission; the mic is only on while the listening indicator shows.",
                        checked = settings.voiceEnabled,
                        onChange = { on ->
                            if (on) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    viewModel.update { it.copy(voiceEnabled = true) }
                                } else {
                                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                viewModel.update { it.copy(voiceEnabled = false, continuousConversation = false) }
                            }
                        },
                    )
                    Text("Language (answers, speech and voice input)", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = settings.language == lang,
                                onClick = { viewModel.update { it.copy(language = lang) } },
                                label = { Text(lang.displayName) },
                            )
                        }
                    }
                    VoiceStatusRow(ui.voiceStatus, settings.language) {
                        runCatching {
                            context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    SwitchRow(
                        title = "Read answers aloud automatically",
                        subtitle = "Speak each scan result and answer. You can always tap Listen / Stop.",
                        checked = settings.autoSpeak,
                        onChange = { on -> viewModel.update { it.copy(autoSpeak = on) } },
                    )
                    SwitchRow(
                        title = "Hands-free follow-up conversation",
                        subtitle = "After a spoken answer, listen again for your next question.",
                        checked = settings.continuousConversation,
                        enabled = settings.voiceEnabled,
                        onChange = { on -> viewModel.update { it.copy(continuousConversation = on) } },
                    )
                    var rate by remember(settings.speechRate) { mutableFloatStateOf(settings.speechRate) }
                    Text("Speech rate: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = rate,
                        onValueChange = { rate = it },
                        onValueChangeFinished = { viewModel.update { it.copy(speechRate = rate) } },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                    )
                    OutlinedButton(onClick = viewModel::testVoice) { Text("Test voice") }
                }
            }

            // ---------------- Live camera
            item {
                SettingsCard("Live camera mode") {
                    SwitchRow(
                        title = "AI scene descriptions in Live mode",
                        subtitle = "Uploads a small frame only when the scene changes and the interval has passed. On-device detection always runs locally.",
                        checked = settings.liveCloudEnabled,
                        onChange = { on -> viewModel.update { it.copy(liveCloudEnabled = on) } },
                    )
                    var interval by remember(settings.liveIntervalSeconds) { mutableFloatStateOf(settings.liveIntervalSeconds.toFloat()) }
                    Text("Minimum time between uploads: ${interval.roundToInt()} s", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = interval,
                        onValueChange = { interval = it },
                        onValueChangeFinished = { viewModel.update { it.copy(liveIntervalSeconds = interval.roundToInt()) } },
                        valueRange = 2f..30f,
                    )
                    var threshold by remember(settings.sceneChangeThreshold) { mutableFloatStateOf(settings.sceneChangeThreshold) }
                    Text("Scene-change sensitivity: ${(threshold * 100).roundToInt()}% difference", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        onValueChangeFinished = { viewModel.update { it.copy(sceneChangeThreshold = threshold) } },
                        valueRange = 0.03f..0.30f,
                    )
                    Text("Max upload image size", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(768, 1024, 1280, 1600).forEach { dim ->
                            FilterChip(
                                selected = settings.maxImageDimension == dim,
                                onClick = { viewModel.update { it.copy(maxImageDimension = dim) } },
                                label = { Text("$dim px") },
                            )
                        }
                    }
                    var quality by remember(settings.jpegQuality) { mutableFloatStateOf(settings.jpegQuality.toFloat()) }
                    Text("JPEG quality: ${quality.roundToInt()}", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = quality,
                        onValueChange = { quality = it },
                        onValueChangeFinished = { viewModel.update { it.copy(jpegQuality = quality.roundToInt()) } },
                        valueRange = 60f..95f,
                    )
                }
            }

            // ---------------- Appearance
            item {
                SettingsCard("Appearance") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.update { it.copy(themeMode = mode) } },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "AI Reality Scanner ${BuildConfig.VERSION_NAME} · AI results can be wrong. Never rely on them for medical, safety-critical or financial decisions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }

    if (showConsentInfo) {
        com.abhijit.airealityscanner.core.ui.components.CloudConsentDialog(
            provider = settings.provider.takeIf { it.isCloud } ?: AiProviderType.ANTHROPIC,
            onAllow = { showConsentInfo = false; viewModel.update { it.copy(cloudConsentGiven = true) } },
            onDecline = { showConsentInfo = false },
        )
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "history") "Delete all history?" else "Delete all images?") },
            text = {
                Text(
                    if (what == "history") "Every scan, image, result, note and conversation will be permanently deleted from this phone."
                    else "All scan photos will be deleted. Text results and conversations are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "history") viewModel.deleteAllHistory() else viewModel.deleteAllImages()
                    confirm = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun CloudProviderConfig(
    provider: AiProviderType,
    hasKey: Boolean,
    keyHint: String,
    model: String,
    presets: List<String>,
    onModel: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    keyUrl: String,
) {
    var key by remember(provider) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (hasKey) Icons.Rounded.CheckCircle else Icons.Rounded.Key,
                contentDescription = null,
                tint = if (hasKey) Color(0xFF34C77B) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasKey) "API key saved (encrypted)" else "No API key yet — get one at $keyUrl",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (hasKey) TextButton(onClick = onClearKey) { Text("Remove") }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (hasKey) "Replace ${provider.displayName} API key" else "${provider.displayName} API key") },
            placeholder = { Text(keyHint) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (visible) "Hide key" else "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSaveKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }

        Text("Model", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                FilterChip(selected = model == preset, onClick = { onModel(preset) }, label = { Text(preset) })
            }
        }
        var custom by remember(model) { mutableStateOf(model) }
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            label = { Text("Model ID (custom)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (custom.isNotBlank() && custom != model) TextButton(onClick = { onModel(custom.trim()) }) { Text("Use") } },
        )
    }
}

@Composable
private fun VoiceStatusRow(status: VoiceAvailability?, language: AppLanguage, onInstall: () -> Unit) {
    val (text, ok) = when (status) {
        VoiceAvailability.AVAILABLE -> "${language.englishName} voice is installed." to true
        VoiceAvailability.MISSING_DATA -> "${language.englishName} voice data is not installed." to false
        VoiceAvailability.NOT_SUPPORTED -> "Your text-to-speech engine doesn't support ${language.englishName}. Try installing Google Speech Services." to false
        VoiceAvailability.ENGINE_UNAVAILABLE -> "No text-to-speech engine available." to false
        VoiceAvailability.UNKNOWN, null -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = if (ok) Color(0xFF34C77B) else Color(0xFFFFB74D),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onInstall) { Text("Install voice data") }
    }
}
