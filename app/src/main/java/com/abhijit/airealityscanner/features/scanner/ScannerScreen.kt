package com.abhijit.airealityscanner.features.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.abhijit.airealityscanner.ai.mlkit.BarcodeInfo
import com.abhijit.airealityscanner.camera.CameraPreview
import com.abhijit.airealityscanner.camera.CameraSetup
import com.abhijit.airealityscanner.core.ui.components.CloudConsentDialog
import com.abhijit.airealityscanner.core.ui.components.GlassPanel
import com.abhijit.airealityscanner.core.ui.components.PulsingDot
import com.abhijit.airealityscanner.core.ui.components.color
import com.abhijit.airealityscanner.core.ui.components.icon
import com.abhijit.airealityscanner.core.ui.theme.Cyan
import com.abhijit.airealityscanner.domain.model.ScanMode
import java.io.File

@Composable
fun ScannerScreen(
    onOpenResult: (String, String?) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val uploads by viewModel.uploadsInFlight.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraDenied by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        cameraDenied = !granted
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startListening() else viewModel.onMicPermissionDenied()
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::onGalleryImage)
    }

    LaunchedEffect(Unit) {
        if (!hasCamera) cameraLauncher.launch(Manifest.permission.CAMERA)
    }

    val controller = remember { CameraSetup.createController(context) }
    val hasCameraState by rememberUpdatedState(hasCamera)

    fun capture(question: String?) {
        if (!hasCameraState) {
            viewModel.showMessage("Camera permission is required to scan. You can import a photo from the gallery instead.")
            return
        }
        controller.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) = viewModel.onImageCaptured(image, question)
                override fun onError(exception: ImageCaptureException) = viewModel.onCaptureError(exception.message)
            },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScannerEvent.OpenResult -> onOpenResult(event.sessionId, event.question)
                is ScannerEvent.Capture -> capture(event.question)
                ScannerEvent.RequestMicPermission -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startListening()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    LaunchedEffect(hasCamera) {
        if (hasCamera) controller.bindToLifecycle(lifecycleOwner)
    }
    val liveRunning = state.live.active && !state.live.paused
    LaunchedEffect(liveRunning, hasCamera) {
        if (!hasCamera) return@LaunchedEffect
        if (liveRunning) {
            controller.setImageAnalysisAnalyzer(viewModel.analysisExecutor, viewModel.liveAnalyzer)
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        } else {
            controller.clearImageAnalysisAnalyzer()
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    LaunchedEffect(state.torchOn, hasCamera) {
        if (hasCamera) controller.enableTorch(state.torchOn)
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopLive()
            viewModel.stopListening()
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraPreview(controller, Modifier.fillMaxSize())
            if (state.live.active) ObjectOverlay(state.live, Modifier.fillMaxSize())
            else ScanReticle(scanning = state.processing, modifier = Modifier.align(Alignment.Center))
        } else {
            CameraPermissionPlaceholder(
                permanentlyDenied = cameraDenied,
                onRequest = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                onOpenAppSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // ---------------- top bar
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassPanel(cornerRadius = 16.dp) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("AI Reality Scanner", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            settings.activeModelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenSettings),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (hasCamera) {
                    RoundIconButton(
                        icon = if (state.torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        description = if (state.torchOn) "Turn flash off" else "Turn flash on",
                        active = state.torchOn,
                        onClick = viewModel::toggleTorch,
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(
                        icon = if (state.live.active) Icons.Rounded.Stop else Icons.Rounded.Videocam,
                        description = if (state.live.active) "Stop live mode" else "Start live mode",
                        active = state.live.active,
                        onClick = { if (state.live.active) viewModel.stopLive() else viewModel.startLive() },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ActivityIndicator(state = state, uploads = uploads, provider = settings.provider.displayName)
        }

        // ---------------- bottom area
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = state.live.active, enter = fadeIn(), exit = fadeOut()) {
                LivePanel(
                    live = state.live,
                    onPause = viewModel::togglePause,
                    onStop = viewModel::stopLive,
                    onToggleCloud = viewModel::toggleLiveCloud,
                    onToggleTranslate = viewModel::toggleTranslate,
                    onToggleNarrate = viewModel::toggleNarrate,
                    onOpenBarcode = { b -> pendingLink = b.url ?: b.rawValue },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            AnimatedVisibility(visible = state.listening || state.partialSpeech.isNotBlank() && state.processing) {
                GlassPanel(Modifier.padding(12.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(Color(0xFFFF5252))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (state.partialSpeech.isBlank()) "Listening… ask a question about what the camera sees"
                            else "“${state.partialSpeech}”",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ModeSelector(selected = settings.lastMode, onSelect = viewModel::selectMode)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RecentThumb(path = recent?.thumbnailPath, onClick = { recent?.let { onOpenResult(it.id, null) } })
                RoundIconButton(
                    icon = Icons.Rounded.PhotoLibrary,
                    description = "Import from gallery",
                    size = 52,
                    onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                ScanButton(
                    busy = state.processing,
                    color = settings.lastMode.color(),
                    onClick = { viewModel.requestCapture() },
                )
                RoundIconButton(
                    icon = if (state.listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    description = if (state.listening) "Stop listening" else "Ask a question by voice",
                    size = 52,
                    active = state.listening,
                    onClick = viewModel::onMicPressed,
                )
                Spacer(Modifier.size(52.dp))
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp))
    }

    if (state.showLiveConsent) {
        CloudConsentDialog(
            provider = settings.provider,
            onAllow = viewModel::acceptLiveConsent,
            onDecline = viewModel::startLiveOnDeviceOnly,
            declineLabel = "On-device only",
        )
    }
    if (state.showVoiceEnable) {
        AlertDialog(
            onDismissRequest = viewModel::dismissVoiceEnable,
            title = { Text("Enable voice features?") },
            text = {
                Text(
                    "Voice questions use your phone's speech recognizer and need microphone permission. " +
                        "The microphone is only active while the red listening indicator is visible.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::enableVoice) { Text("Enable") } },
            dismissButton = { TextButton(onClick = viewModel::dismissVoiceEnable) { Text("Cancel") } },
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
                    runCatching { uriHandler.openUri(link) }.onFailure { viewModel.showMessage("Can't open this code as a link.") }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActivityIndicator(state: ScannerUiState, uploads: Int, provider: String) {
    val live = state.live
    val (text, color) = when {
        uploads > 0 || live.uploading -> "Uploading frame to $provider…" to Color(0xFFFFB74D)
        state.listening -> "Microphone on — listening" to Color(0xFFFF5252)
        live.active && live.paused -> "Live mode paused — camera frames are not being analysed" to Color(0xFFB0BEC5)
        live.active && live.cloudEnabled -> "LIVE · on-device analysis + AI descriptions every few seconds" to Color(0xFF34C77B)
        live.active -> "LIVE · on-device analysis only (nothing uploaded)" to Color(0xFF34C77B)
        state.processing -> "Processing photo…" to Cyan
        else -> return
    }
    GlassPanel(cornerRadius = 14.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color, size = 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Int = 44,
    active: Boolean = false,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (active) Cyan.copy(alpha = 0.85f) else Color(0x99101722))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Color.Black else Color.White)
    }
}

@Composable
private fun ScanButton(busy: Boolean, color: Color, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (busy) 0.9f else 1f, label = "scanScale")
    Box(
        Modifier
            .size((78 * scale).dp)
            .clip(CircleShape)
            .border(4.dp, color, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = "Scan" },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.Black)
    }
}

@Composable
private fun RecentThumb(path: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x99101722))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .clickable(enabled = path != null, onClick = onClick)
            .semantics { contentDescription = "Open most recent scan" },
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ModeSelector(selected: ScanMode, onSelect: (ScanMode) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ScanMode.entries) { mode ->
            val isSelected = mode == selected
            val accent = mode.color()
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) accent else Color(0x99101722))
                    .border(1.dp, if (isSelected) accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .semantics { contentDescription = "${mode.title} mode${if (isSelected) ", selected" else ""}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mode.icon(), contentDescription = null, tint = if (isSelected) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(mode.title, color = if (isSelected) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ScanReticle(scanning: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reticle")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "sweep")
    Canvas(modifier.size(250.dp)) {
        val len = size.minDimension * 0.18f
        val stroke = 5.dp.toPx()
        val c = Color.White.copy(alpha = 0.9f)
        val w = size.width
        val h = size.height
        // four corner brackets
        drawLine(c, Offset(0f, 0f), Offset(len, 0f), stroke, StrokeCap.Round)
        drawLine(c, Offset(0f, 0f), Offset(0f, len), stroke, StrokeCap.Round)
        drawLine(c, Offset(w, 0f), Offset(w - len, 0f), stroke, StrokeCap.Round)
        drawLine(c, Offset(w, 0f), Offset(w, len), stroke, StrokeCap.Round)
        drawLine(c, Offset(0f, h), Offset(len, h), stroke, StrokeCap.Round)
        drawLine(c, Offset(0f, h), Offset(0f, h - len), stroke, StrokeCap.Round)
        drawLine(c, Offset(w, h), Offset(w - len, h), stroke, StrokeCap.Round)
        drawLine(c, Offset(w, h), Offset(w, h - len), stroke, StrokeCap.Round)
        if (scanning) {
            val y = h * sweep
            drawLine(Cyan, Offset(8f, y), Offset(w - 8f, y), 3.dp.toPx(), StrokeCap.Round)
        } else {
            drawCircle(Cyan.copy(alpha = 0.8f), radius = 4.dp.toPx())
        }
    }
}

/** Draws on-device object boxes, mapping analysis-frame coordinates onto the FILL_CENTER preview. */
@Composable
private fun ObjectOverlay(live: LiveState, modifier: Modifier = Modifier) {
    if (live.frameWidth == 0 || live.frameHeight == 0) return
    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 38f
            isAntiAlias = true
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier) {
        val scale = maxOf(size.width / live.frameWidth, size.height / live.frameHeight)
        val dx = (size.width - live.frameWidth * scale) / 2f
        val dy = (size.height - live.frameHeight * scale) / 2f
        live.objects.forEach { obj ->
            val left = obj.box.left * scale + dx
            val top = obj.box.top * scale + dy
            val width = obj.box.width() * scale
            val height = obj.box.height() * scale
            drawRoundRect(
                color = Cyan,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                style = Stroke(width = 3.dp.toPx()),
            )
            obj.label?.let { label ->
                val pct = obj.confidence?.let { " ${(it * 100).toInt()}%" } ?: ""
                drawContext.canvas.nativeCanvas.drawText("$label$pct", left + 12f, top + 44f, labelPaint)
            }
        }
        live.barcodes.forEach { code ->
            val box = code.box ?: return@forEach
            drawRect(
                color = Color(0xFFFFD54F),
                topLeft = Offset(box.left * scale + dx, box.top * scale + dy),
                size = Size(box.width() * scale, box.height() * scale),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}

@Composable
private fun LivePanel(
    live: LiveState,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onToggleCloud: () -> Unit,
    onToggleTranslate: () -> Unit,
    onToggleNarrate: () -> Unit,
    onOpenBarcode: (BarcodeInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        live.paused -> "Paused"
                        live.uploading -> "Describing scene…"
                        live.processingOnDevice -> "Analysing on device…"
                        else -> "Watching for changes"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (live.cloudEnabled) {
                    Text(
                        "${live.uploads} upload${if (live.uploads == 1) "" else "s"} · change ${(live.sceneChange * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            live.description?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
            if (live.objects.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Objects: " + live.objects.joinToString { it.label ?: "object" },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (live.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Text in view", color = Cyan, style = MaterialTheme.typography.labelMedium)
                Text(live.text, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            live.translatedText?.let {
                Spacer(Modifier.height(6.dp))
                Text("Translation (on-device)", color = Cyan, style = MaterialTheme.typography.labelMedium)
                Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            live.barcodes.forEach { code ->
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x33FFD54F))
                        .clickable { onOpenBarcode(code) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = Color(0xFFFFD54F))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${code.format} · ${code.type}: ${code.displayValue}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            live.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallToggle(if (live.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (live.paused) "Resume" else "Pause", false, onPause)
                SmallToggle(if (live.cloudEnabled) Icons.Rounded.Cloud else Icons.Rounded.CloudOff, "AI descriptions", live.cloudEnabled, onToggleCloud)
                SmallToggle(Icons.Rounded.Translate, "Translate text", live.translateEnabled, onToggleTranslate)
                SmallToggle(
                    if (live.narrate) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    "Speak descriptions", live.narrate, onToggleNarrate,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun SmallToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Cyan.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f)),
    ) {
        Icon(icon, contentDescription = description, tint = if (active) Color.Black else Color.White)
    }
}

@Composable
private fun CameraPermissionPlaceholder(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.NoPhotography, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        Text("Camera access needed", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(
            "AI Reality Scanner uses the camera only while this screen is open. Photos are analysed only when you tap Scan " +
                "or start Live mode. You can also import a photo from your gallery.",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenAppSettings) { Text("Open app settings") }
            TextButton(onClick = onRequest) { Text("Ask again") }
        } else {
            Button(onClick = onRequest) { Text("Allow camera") }
        }
    }
}
