package com.abhijit.airealityscanner.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PrecisionManufacturing
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhijit.airealityscanner.domain.model.AiProviderType
import com.abhijit.airealityscanner.domain.model.Confidence
import com.abhijit.airealityscanner.domain.model.ScanMode

fun ScanMode.icon(): ImageVector = when (this) {
    ScanMode.GENERAL -> Icons.Rounded.Category
    ScanMode.PLANT -> Icons.Rounded.Eco
    ScanMode.DOCUMENT -> Icons.Rounded.Description
    ScanMode.PRODUCT -> Icons.Rounded.ShoppingBag
    ScanMode.MACHINE -> Icons.Rounded.PrecisionManufacturing
    ScanMode.LANDMARK -> Icons.Rounded.AccountBalance
    ScanMode.MEDICINE -> Icons.Rounded.Medication
    ScanMode.ANIMAL -> Icons.Rounded.Pets
    ScanMode.CURRENCY -> Icons.Rounded.Payments
}

fun ScanMode.color(): Color = hexColor(colorHex)

fun hexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF4FD8EB))

/** Frosted, translucent panel for overlays on top of the camera. Used sparingly. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .background(Color(0xCC0D1219), shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
    ) { content() }
}

@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(modifier.size(size).alpha(alpha).background(color, CircleShape))
}

@Composable
fun ConfidenceBadge(confidence: Confidence, modifier: Modifier = Modifier) {
    val (color, icon) = when (confidence) {
        Confidence.HIGH -> Color(0xFF34C77B) to Icons.Rounded.CheckCircle
        Confidence.MEDIUM -> Color(0xFF4FA3EB) to Icons.Rounded.CheckCircle
        Confidence.LOW -> Color(0xFFFFB74D) to Icons.Rounded.HelpOutline
        Confidence.UNCERTAIN -> Color(0xFFFF6B6B) to Icons.Rounded.ErrorOutline
    }
    Row(
        modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(confidence.label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                } else {
                    Box(Modifier.size(width = 4.dp, height = 18.dp).background(accent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
            }
        }
    }
}

@Composable
fun BulletList(items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row {
                Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                MarkdownText(item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFFFFB74D).copy(alpha = 0.14f), MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Explains exactly what will be uploaded before any image leaves the phone. */
@Composable
fun CloudConsentDialog(
    provider: AiProviderType,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    declineLabel: String = "Not now",
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Send image to ${provider.displayName}?") },
        text = {
            Text(
                "To analyse what you scan, AI Reality Scanner sends the photo (resized and compressed) and your " +
                    "questions to ${provider.displayName} over an encrypted connection, using your own API key. " +
                    "Images are only sent when you scan, ask a question, or run Live mode with cloud descriptions on — " +
                    "a visible indicator shows every upload.\n\n" +
                    "Scans are saved only on this phone. You can revoke this permission or delete all history at any time in Settings.",
            )
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow cloud analysis") } },
        dismissButton = { TextButton(onClick = onDecline) { Text(declineLabel) } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
