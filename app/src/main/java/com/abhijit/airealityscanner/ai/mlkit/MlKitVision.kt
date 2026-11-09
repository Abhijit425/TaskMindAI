package com.abhijit.airealityscanner.ai.mlkit

import android.graphics.Bitmap
import android.graphics.Rect
import com.abhijit.airealityscanner.domain.model.AppLanguage
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class OcrBlock(val text: String, val box: Rect?)
data class OcrResult(val text: String, val blocks: List<OcrBlock>)
data class BarcodeInfo(
    val rawValue: String,
    val displayValue: String,
    val format: String,
    val type: String,
    val url: String?,
    val box: Rect?,
)
data class LabelInfo(val text: String, val confidence: Float)
data class DetectedObjectInfo(val box: Rect, val label: String?, val confidence: Float?)
data class TranslationResult(val sourceLanguage: String, val targetLanguage: String, val text: String)

/**
 * All on-device Google ML Kit features: OCR (Latin + Devanagari), barcodes/QR, image labelling,
 * object detection, language identification and offline translation. Nothing here uploads images.
 */
@Singleton
class MlKitVision @Inject constructor() {

    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagariRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())
    }
    private val objectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build(),
        )
    }
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    /** Hindi uses the Devanagari model (which also reads Latin). Bengali script is not supported by ML Kit OCR. */
    suspend fun recognizeText(bitmap: Bitmap, language: AppLanguage = AppLanguage.ENGLISH): OcrResult {
        val recognizer = if (language == AppLanguage.HINDI) devanagariRecognizer else latinRecognizer
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return OcrResult(result.text, result.textBlocks.map { OcrBlock(it.text, it.boundingBox) })
    }

    suspend fun scanBarcodes(bitmap: Bitmap): List<BarcodeInfo> =
        barcodeScanner.process(InputImage.fromBitmap(bitmap, 0)).await().map { b ->
            BarcodeInfo(
                rawValue = b.rawValue.orEmpty(),
                displayValue = b.displayValue ?: b.rawValue.orEmpty(),
                format = formatName(b.format),
                type = typeName(b.valueType),
                url = b.url?.url,
                box = b.boundingBox,
            )
        }

    suspend fun labelImage(bitmap: Bitmap): List<LabelInfo> =
        labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
            .map { LabelInfo(it.text, it.confidence) }
            .sortedByDescending { it.confidence }

    suspend fun detectObjects(bitmap: Bitmap): List<DetectedObjectInfo> =
        objectDetector.process(InputImage.fromBitmap(bitmap, 0)).await().map { obj ->
            val best = obj.labels.maxByOrNull { it.confidence }
            DetectedObjectInfo(obj.boundingBox, best?.text, best?.confidence)
        }

    suspend fun identifyLanguage(text: String): String =
        runCatching { languageIdentifier.identifyLanguage(text).await() }.getOrDefault("und")

    /**
     * Translates text on-device. The first use of a language pair downloads a ~30 MB model
     * (from Google), after which translation works offline.
     */
    suspend fun translate(text: String, target: AppLanguage): TranslationResult {
        val detected = identifyLanguage(text)
        val source = TranslateLanguage.fromLanguageTag(detected) ?: TranslateLanguage.ENGLISH
        val targetCode = TranslateLanguage.fromLanguageTag(target.code) ?: TranslateLanguage.ENGLISH
        if (source == targetCode) return TranslationResult(source, targetCode, text)
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(targetCode).build(),
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            TranslationResult(source, targetCode, translator.translate(text).await())
        } finally {
            translator.close()
        }
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR code"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        else -> "Barcode"
    }

    private fun typeName(type: Int): String = when (type) {
        Barcode.TYPE_URL -> "Link"
        Barcode.TYPE_WIFI -> "Wi-Fi"
        Barcode.TYPE_CONTACT_INFO -> "Contact"
        Barcode.TYPE_EMAIL -> "Email"
        Barcode.TYPE_PHONE -> "Phone"
        Barcode.TYPE_SMS -> "SMS"
        Barcode.TYPE_GEO -> "Location"
        Barcode.TYPE_CALENDAR_EVENT -> "Calendar event"
        Barcode.TYPE_PRODUCT -> "Product code"
        Barcode.TYPE_ISBN -> "ISBN"
        Barcode.TYPE_TEXT -> "Text"
        else -> "Data"
    }
}
