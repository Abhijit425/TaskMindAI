@file:OptIn(ExperimentalMaterial3Api::class)

package com.abhijit.airealityscanner.features.result

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.abhijit.airealityscanner.core.ui.components.BulletList
import com.abhijit.airealityscanner.core.ui.components.CloudConsentDialog
import com.abhijit.airealityscanner.core.ui.components.ConfidenceBadge
import com.abhijit.airealityscanner.core.ui.components.ExpandableSection
import com.abhijit.airealityscanner.core.ui.components.MarkdownText
import com.abhijit.airealityscanner.core.ui.components.PulsingDot
import com.abhijit.airealityscanner.core.ui.components.WarningCard
import com.abhijit.airealityscanner.core.ui.components.color
import com.abhijit.airealityscanner.core.ui.components.icon
import com.abhijit.airealityscanner.core.ui.theme.MonoStyle
import com.abhijit.airealityscanner.core.util.TextFormat
import com.abhijit.airealityscanner.domain.model.AppLanguage
import com.abhijit.airealityscanner.domain.model.ChatMessage
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ScanSession
import java.io.File

@Composable
fun ResultScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val session by viewModel.session.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val uploads by viewModel.uploadsInFlight.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startListening() else viewModel.onMicPermissionDenied()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ResultEvent.ShareText -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_TEXT, event.text)
                        if (event.imageUri != null) {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, event.imageUri)
                            clipData = ClipData.newRawUri("scan", event.imageUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } else {
                            type = "text/plain"
                        }
                    }
                    context.startActivity(Intent.createChooser(intent, "Share scan"))
                }
                is ResultEvent.ShareFile -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        clipData = ClipData.newRawUri("report", event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Export scan report"))
                }
                ResultEvent.RequestMicPermission -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startListening()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                ResultEvent.Closed -> onBack()
            }
        }
    }
    LaunchedEffect(ui.snackbar) {
        ui.snackbar?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeSnackbar()
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        session?.displayTitle ?: "Scan",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val s = session
                    if (s != null) {
                        IconButton(onClick = {
                            if (speaking) viewModel.stopSpeaking()
                            else s.analysis?.let { viewModel.speak(it.spokenSummary()) }
                        }) {
                            Icon(
                                if (speaking) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = if (speaking) "Stop speaking" else "Listen to result",
                            )
                        }
                        IconButton(onClick = viewModel::toggleSaved) {
                            Icon(
                                if (s.isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                contentDescription = if (s.isSaved) "Saved — tap to unsave" else "Save scan",
                            )
                        }
                        IconButton(onClick = viewModel::share) { Icon(Icons.Rounded.Share, contentDescription = "Share") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (s.isFavourite) "Remove favourite" else "Add to favourites") },
                                    leadingIcon = { Icon(if (s.isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) },
                                    onClick = { menuOpen = false; viewModel.toggleFavourite() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export PDF report") },
                                    leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) },
                                    onClick = { menuOpen = false; viewModel.exportPdf() },
                                )
                                if (s.images.isNotEmpty() && s.analysis != null) {
                                    DropdownMenuItem(
                                        text = { Text("Re-analyse") },
                                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                        onClick = { menuOpen = false; viewModel.analyze() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete image only") },
                                        leadingIcon = { Icon(Icons.Rounded.HideImage, null) },
                                        onClick = { menuOpen = false; viewModel.deleteImages() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete scan") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                value = input,
                onValueChange = { input = it },
                enabled = !ui.answering && session != null,
                listening = ui.listening,
                partial = ui.partialSpeech,
                onSend = {
                    viewModel.ask(input)
                    input = ""
                },
                onMic = viewModel::onMicPressed,
            )
        },
    ) { padding ->
        val s = session
        when {
            ui.notFound -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This scan no longer exists.")
            }
            s == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (s.images.isNotEmpty()) {
                    item(key = "images") { ImageStrip(s) }
                }
                if (uploads > 0 || ui.analyzing) {
                    item(key = "progress") {
                        AnalysisProgress(uploading = uploads > 0, provider = settings.activeModelLabel, analyzing = ui.analyzing)
                    }
                }
                ui.error?.let { err ->
                    item(key = "error") {
                        ErrorCard(
                            message = err,
                            onRetry = if (s.images.isNotEmpty()) viewModel::analyze else null,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
                s.analysis?.let { analysis ->
                    analysisItems(
                        analysis = analysis,
                        mode = s.mode,
                        translation = ui.translation,
                        translating = ui.translating,
                        onTranslate = viewModel::translateExtractedText,
                    )
                }
                if (s.mode == ScanMode.MEDICINE) {
                    item(key = "medicine") {
                        WarningCard(
                            "This app only reads what is printed on the label. It does not give medical advice, " +
                                "prescribe or adjust doses. Always consult a doctor or pharmacist before taking any medicine.",
                        )
                    }
                }
                if (ui.barcodes.isNotEmpty()) {
                    item(key = "codes") {
                        ExpandableSection("Barcodes & QR codes (on-device)", icon = Icons.Rounded.QrCode2) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ui.barcodes.forEach { code ->
                                    Text(
                                        "${code.format} · ${code.type}\n${code.displayValue}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { pendingLink = code.url ?: code.rawValue }
                                            .padding(6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "notes") {
                    NotesField(initial = s.notes, onChange = viewModel::updateNotes)
                }
                if (messages.isNotEmpty() || s.analysis != null || s.images.isEmpty()) {
                    item(key = "conversation-header") {
                        Text(
                            if (s.images.isEmpty()) "Conversation" else "Ask anything about this scan",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (messages.isEmpty() && (s.analysis != null || s.images.isEmpty())) {
                    item(key = "suggestions") {
                        SuggestionChips(mode = s.mode, hasImage = s.images.isNotEmpty(), onPick = { viewModel.ask(it) })
                    }
                }
                items(messages, key = { "m${it.id}" }) { message ->
                    MessageBubble(message = message, onSpeak = { viewModel.speak(it) })
                }
                if (ui.answering) {
                    item(key = "typing") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "footer") {
                    Text(
                        "${s.mode.title} scan · ${TextFormat.dateTime(s.createdAt)}" +
                            if (s.providerName.isNotBlank()) " · ${s.providerName}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    ui.consentPending?.let {
        CloudConsentDialog(
            provider = settings.provider,
            onAllow = viewModel::acceptConsent,
            onDecline = viewModel::declineConsent,
        )
    }
    if (ui.showVoiceEnable) {
        AlertDialog(
            onDismissRequest = viewModel::dismissVoiceEnable,
            title = { Text("Enable voice features?") },
            text = { Text("Voice questions need microphone permission. The microphone is only on while the listening indicator is shown.") },
            confirmButton = { TextButton(onClick = viewModel::enableVoice) { Text("Enable") } },
            dismissButton = { TextButton(onClick = viewModel::dismissVoiceEnable) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this scan?") },
            text = { Text("The photo, results, notes and conversation will be permanently removed from this phone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    pendingLink?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("Open scanned code?") },
            text = { Text("This code contains:\n\n$link\n\nOnly open links you trust.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    runCatching { uriHandler.openUri(link) }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("Cancel") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.analysisItems(
    analysis: ScanAnalysis,
    mode: ScanMode,
    translation: String?,
    translating: Boolean,
    onTranslate: (AppLanguage) -> Unit,
) {
    item(key = "subject") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(mode.icon(), contentDescription = null, tint = mode.color(), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(mode.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = mode.color())
            }
            Text(analysis.subject, style = MaterialTheme.typography.headlineSmall)
            ConfidenceBadge(analysis.confidence)
            if (analysis.confidenceNote.isNotBlank()) {
                Text(analysis.confidenceNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (analysis.summary.isNotBlank()) {
                SelectionContainer { MarkdownText(analysis.summary, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    analysis.sections.forEachIndexed { index, section ->
        item(key = "section-$index") {
            ExpandableSection(section.title, initiallyExpanded = index < 3, accent = mode.color()) {
                BulletList(section.items)
            }
        }
    }
    if (analysis.keyFacts.isNotEmpty()) {
        item(key = "facts") {
            ExpandableSection("Important facts", icon = Icons.Rounded.Lightbulb) { BulletList(analysis.keyFacts) }
        }
    }
    if (analysis.applications.isNotEmpty()) {
        item(key = "applications") {
            ExpandableSection("Practical applications", icon = Icons.Rounded.Build, initiallyExpanded = false) {
                BulletList(analysis.applications)
            }
        }
    }
    if (analysis.safety.isNotEmpty()) {
        item(key = "safety") {
            ExpandableSection("Safety considerations", icon = Icons.Rounded.HealthAndSafety, accent = Color(0xFFFFB74D)) {
                BulletList(analysis.safety)
            }
        }
    }
    if (analysis.verificationNote.isNotBlank()) {
        item(key = "verification") {
            ExpandableSection("Observed vs. AI-inferred", icon = Icons.Rounded.Verified, initiallyExpanded = false) {
                Text(analysis.verificationNote, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    analysis.extractedText?.takeIf { it.isNotBlank() }?.let { text ->
        item(key = "ocr") {
            ExtractedTextCard(text, translation, translating, onTranslate)
        }
    }
    item(key = "disclaimer") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "AI-generated by ${analysis.providerName}. It can be wrong — verify anything important.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImageStrip(session: ScanSession) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        session.images.forEach { img ->
            val ratio = if (img.height > 0) img.width.toFloat() / img.height else 1f
            AsyncImage(
                model = File(img.path),
                contentDescription = "Scanned image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .then(if (session.images.size == 1) Modifier.fillMaxWidth() else Modifier.width(220.dp))
                    .aspectRatio(ratio.coerceIn(0.6f, 1.8f))
                    .clip(MaterialTheme.shapes.large),
            )
        }
    }
}

@Composable
private fun AnalysisProgress(uploading: Boolean, provider: String, analyzing: Boolean) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(if (uploading) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        uploading -> "Uploading image to $provider and analysing…"
                        analyzing -> "Analysing on device…"
                        else -> "Working…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: (() -> Unit)?, onOpenSettings: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Try again") }
                OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun ExtractedTextCard(
    text: String,
    translation: String?,
    translating: Boolean,
    onTranslate: (AppLanguage) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    ExpandableSection("Extracted text", icon = Icons.Rounded.TextSnippet, initiallyExpanded = false) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionContainer { Text(text, style = MonoStyle) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    label = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)) },
                )
                Box {
                    AssistChip(
                        onClick = { menu = true },
                        label = { Text(if (translating) "Translating…" else "Translate") },
                        leadingIcon = { Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        AppLanguage.entries.forEach { lang ->
                            DropdownMenuItem(text = { Text("To ${lang.englishName}") }, onClick = { menu = false; onTranslate(lang) })
                        }
                    }
                }
            }
            translation?.let {
                Text("Translation (on-device ML Kit)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun NotesField(initial: String, onChange: (String) -> Unit) {
    var notes by rememberSaveable { mutableStateOf(initial) }
    ExpandableSection("Personal notes", icon = Icons.Rounded.EditNote, initiallyExpanded = initial.isNotBlank()) {
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; onChange(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Add your own notes…") },
            minLines = 2,
        )
    }
}

@Composable
private fun SuggestionChips(mode: ScanMode, hasImage: Boolean, onPick: (String) -> Unit) {
    val suggestions = if (!hasImage) {
        listOf("How does a refrigerator work?", "Explain photosynthesis simply", "Why is the sky blue?")
    } else when (mode) {
        ScanMode.GENERAL -> listOf("How does it work?", "What is it made of?", "How do I maintain it?")
        ScanMode.PLANT -> listOf("Is it safe for pets?", "How often should I water it?", "Why are the leaves yellow?")
        ScanMode.DOCUMENT -> listOf("Summarise this in 3 points", "What is the total amount?", "Any due dates?")
        ScanMode.PRODUCT -> listOf("What are alternatives?", "What should I check before buying?", "How do I use it?")
        ScanMode.MACHINE -> listOf("How does it work inside?", "What safety precautions?", "Any visible problems?")
        ScanMode.LANDMARK -> listOf("Tell me its history", "What architectural style is this?", "Interesting facts?")
        ScanMode.MEDICINE -> listOf("What does the label say about storage?", "List the printed warnings", "Read the ingredients")
        ScanMode.ANIMAL -> listOf("Is it dangerous?", "What does it eat?", "Where does it live?")
        ScanMode.CURRENCY -> listOf("Which country is this from?", "What security features are visible?", "What is the denomination?")
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { s -> AssistChip(onClick = { onPick(s) }, label = { Text(s) }) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSpeak: (String) -> Unit) {
    val isUser = message.role == ChatRole.USER
    val isSystem = message.role == ChatRole.SYSTEM
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp,
            ),
            color = when {
                isSystem -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer {
                    MarkdownText(
                        message.text,
                        color = when {
                            isSystem -> MaterialTheme.colorScheme.onErrorContainer
                            isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (message.role == ChatRole.ASSISTANT) {
                    Text(
                        "Listen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).clickable { onSpeak(message.text) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    listening: Boolean,
    partial: String,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            AnimatedVisibility(listening) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(Color(0xFFFF5252))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (partial.isBlank()) "Listening…" else partial,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask a follow-up question…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onMic) {
                    Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, contentDescription = if (listening) "Stop listening" else "Ask by voice")
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
