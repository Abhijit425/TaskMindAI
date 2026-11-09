package com.abhijit.airealityscanner.ai.ondevice

import android.graphics.Bitmap
import com.abhijit.airealityscanner.ai.mlkit.MlKitVision
import com.abhijit.airealityscanner.domain.model.AnalysisSection
import com.abhijit.airealityscanner.domain.model.AppLanguage
import com.abhijit.airealityscanner.domain.model.Confidence
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import com.abhijit.airealityscanner.domain.model.ScanMode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Offline analysis with Google ML Kit. It reports exactly what the on-device models detect
 * (generic labels with confidence, objects, text and barcodes) and is explicit about its limits.
 */
@Singleton
class OnDeviceAnalyzer @Inject constructor(
    private val ml: MlKitVision,
) {
    suspend fun analyze(mode: ScanMode, bitmap: Bitmap, language: AppLanguage): ScanAnalysis = coroutineScope {
        val labelsJob = async { runCatching { ml.labelImage(bitmap) }.getOrDefault(emptyList()) }
        val objectsJob = async { runCatching { ml.detectObjects(bitmap) }.getOrDefault(emptyList()) }
        val textJob = async { runCatching { ml.recognizeText(bitmap, language).text }.getOrDefault("") }
        val barcodesJob = async { runCatching { ml.scanBarcodes(bitmap) }.getOrDefault(emptyList()) }
        val labels = labelsJob.await()
        val objects = objectsJob.await()
        val text = textJob.await().trim()
        val barcodes = barcodesJob.await()

        val top = labels.firstOrNull()
        val confidence = when {
            top == null -> Confidence.UNCERTAIN
            top.confidence >= 0.85f -> Confidence.HIGH
            top.confidence >= 0.70f -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val subject = when {
            mode == ScanMode.DOCUMENT && text.isNotEmpty() -> "Document text"
            top == null && barcodes.isNotEmpty() -> barcodes.first().format
            top == null -> "Not confidently identified"
            top.confidence >= 0.70f -> top.text
            else -> "Possibly: ${top.text}"
        }

        val sections = buildList {
            if (labels.isNotEmpty()) add(
                AnalysisSection(
                    "On-device labels",
                    labels.take(8).map { "${it.text} — ${(it.confidence * 100).roundToInt()}%" },
                ),
            )
            if (objects.isNotEmpty()) add(
                AnalysisSection(
                    "Detected objects",
                    listOf("${objects.size} object(s) located in the frame") +
                        objects.mapNotNull { o -> o.label?.let { l -> "$l${o.confidence?.let { c -> " — ${(c * 100).roundToInt()}%" } ?: ""}" } },
                ),
            )
            if (barcodes.isNotEmpty()) add(
                AnalysisSection("Barcodes / QR codes", barcodes.map { "${it.format} (${it.type}): ${it.displayValue}" }),
            )
        }

        val summary = buildString {
            if (top != null) {
                append("ML Kit's general image labeller suggests \"${top.text}\" with ${(top.confidence * 100).roundToInt()}% confidence. ")
            } else {
                append("The on-device model could not confidently label this image. ")
            }
            if (text.isNotEmpty()) append("Text was detected in the image (see below). ")
            if (barcodes.isNotEmpty()) append("${barcodes.size} barcode(s) were decoded. ")
        }.trim()

        ScanAnalysis(
            subject = subject,
            confidence = confidence,
            confidenceNote = if (top != null) "Based on ML Kit's ~400 generic labels, not a specialist model." else "No label passed the 50% confidence threshold.",
            summary = summary,
            sections = sections,
            keyFacts = emptyList(),
            applications = emptyList(),
            safety = if (mode == ScanMode.MEDICINE) listOf("Always follow the printed label and consult a doctor or pharmacist before taking any medicine.") else emptyList(),
            verificationNote = "Offline mode recognises broad categories (e.g. 'Plant', 'Vehicle'), text and barcodes. " +
                "It cannot name species, product models or landmarks. Switch to a cloud AI provider in Settings for detailed explanations and follow-up questions.",
            extractedText = text.ifEmpty { null },
            rawText = summary,
            providerName = "On-device ML Kit",
        )
    }
}
