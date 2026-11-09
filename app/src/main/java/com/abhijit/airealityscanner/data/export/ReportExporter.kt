package com.abhijit.airealityscanner.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.abhijit.airealityscanner.core.util.ImageUtils
import com.abhijit.airealityscanner.core.util.TextFormat
import com.abhijit.airealityscanner.domain.model.ChatMessage
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.ScanSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Generates shareable scan reports (PDF), plain text and a JSON export of history. */
@Singleton
class ReportExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val exportDir: File
        get() = File(context.cacheDir, "exports").apply { mkdirs() }

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareText(session: ScanSession, messages: List<ChatMessage>): String = buildString {
        appendLine("AI Reality Scanner — ${session.mode.title} scan")
        appendLine(TextFormat.dateTime(session.createdAt))
        appendLine()
        session.analysis?.let { appendLine(it.toPlainText()); appendLine() }
        val convo = messages.filter { it.role != ChatRole.SYSTEM }
        if (convo.isNotEmpty()) {
            appendLine("Conversation")
            convo.forEach { m ->
                appendLine("${if (m.role == ChatRole.USER) "You" else "AI"}: ${TextFormat.stripMarkdown(m.text)}")
            }
            appendLine()
        }
        if (session.notes.isNotBlank()) appendLine("Notes: ${session.notes}")
        appendLine()
        append("AI-generated content may be inaccurate. Verify important information.")
    }.trim()

    suspend fun exportPdf(session: ScanSession, messages: List<ChatMessage>): Uri = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val writer = PdfWriter(doc)
        writer.newPage()
        writer.text("AI Reality Scanner — Scan Report", paint(20f, bold = true, color = Color.rgb(0, 120, 140)))
        writer.text("${session.mode.title} scan · ${TextFormat.dateTime(session.createdAt)}", paint(10f, color = Color.GRAY))
        if (session.providerName.isNotBlank()) writer.text("Analysed with: ${session.providerName}", paint(10f, color = Color.GRAY))
        writer.space(6f)

        session.images.forEach { img ->
            ImageUtils.decodeFile(img.path, 1200)?.let { writer.image(it); it.recycle() }
        }

        val analysis = session.analysis
        if (analysis != null) {
            writer.text(analysis.subject, paint(16f, bold = true))
            val note = if (analysis.confidenceNote.isNotBlank()) " — ${analysis.confidenceNote}" else ""
            writer.text("${analysis.confidence.label}$note", paint(10f, color = Color.DKGRAY))
            if (analysis.summary.isNotBlank()) writer.text(analysis.summary, paint(11f))
            analysis.sections.forEach { section ->
                writer.text(section.title, paint(12.5f, bold = true))
                section.items.forEach { writer.text("•  $it", paint(11f), spacingAfter = 2f) }
                writer.space(4f)
            }
            fun list(title: String, items: List<String>) {
                if (items.isEmpty()) return
                writer.text(title, paint(12.5f, bold = true))
                items.forEach { writer.text("•  $it", paint(11f), spacingAfter = 2f) }
                writer.space(4f)
            }
            list("Important facts", analysis.keyFacts)
            list("Practical applications", analysis.applications)
            list("Safety considerations", analysis.safety)
            if (analysis.verificationNote.isNotBlank()) {
                writer.text("Verification", paint(12.5f, bold = true))
                writer.text(analysis.verificationNote, paint(11f, color = Color.DKGRAY))
            }
            if (!analysis.extractedText.isNullOrBlank()) {
                writer.text("Extracted text", paint(12.5f, bold = true))
                writer.text(analysis.extractedText, paint(10f, mono = true))
            }
        }

        val convo = messages.filter { it.role != ChatRole.SYSTEM }
        if (convo.isNotEmpty()) {
            writer.space(8f)
            writer.text("Conversation", paint(14f, bold = true))
            convo.forEach { m ->
                writer.text(if (m.role == ChatRole.USER) "You" else "AI", paint(10.5f, bold = true, color = Color.rgb(0, 120, 140)), spacingAfter = 1f)
                writer.text(TextFormat.stripMarkdown(m.text), paint(11f))
            }
        }
        if (session.notes.isNotBlank()) {
            writer.space(8f)
            writer.text("Personal notes", paint(12.5f, bold = true))
            writer.text(session.notes, paint(11f))
        }
        writer.space(10f)
        writer.text(
            "Generated by AI Reality Scanner. AI-generated identifications and facts may be wrong; " +
                "verify important information with reliable sources or a qualified professional.",
            paint(9f, color = Color.GRAY),
        )
        writer.finish()

        val file = File(exportDir, "scan-report-${session.id.take(8)}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        uriFor(file)
    }

    suspend fun exportHistoryJson(sessions: List<ScanSession>): Uri = withContext(Dispatchers.IO) {
        val array = buildJsonArray {
            sessions.forEach { s ->
                addJsonObject {
                    put("id", s.id)
                    put("title", s.displayTitle)
                    put("mode", s.mode.id)
                    put("createdAt", s.createdAt)
                    put("favourite", s.isFavourite)
                    put("notes", s.notes)
                    put("provider", s.providerName)
                    s.analysis?.let { a ->
                        put("subject", a.subject)
                        put("confidence", a.confidence.name.lowercase())
                        put("summary", a.summary)
                        putJsonArray("keyFacts") { a.keyFacts.forEach { add(it) } }
                        put("extractedText", a.extractedText ?: "")
                        put("fullText", a.toPlainText())
                    }
                }
            }
        }
        val file = File(exportDir, "ai-reality-scanner-history.json")
        file.writeText(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), array))
        uriFor(file)
    }

    fun clearExports() {
        exportDir.listFiles()?.forEach { it.delete() }
    }

    private fun paint(size: Float, bold: Boolean = false, mono: Boolean = false, color: Int = Color.BLACK) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.DEFAULT
            }
        }

    /** Minimal paginating text/image writer for A4 pages. */
    private class PdfWriter(private val doc: PdfDocument) {
        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 40f
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = margin
        private val canvas: Canvas get() = page!!.canvas

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin
        }

        fun space(amount: Float) {
            y += amount
        }

        fun text(text: String, paint: TextPaint, spacingAfter: Float = 6f) {
            if (text.isEmpty()) return
            val width = (pageWidth - 2 * margin).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
            for (i in 0 until layout.lineCount) {
                val lineHeight = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
                if (y + lineHeight > pageHeight - margin) newPage()
                val line = text.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd('\n')
                canvas.drawText(line, margin, y - paint.fontMetrics.ascent, paint)
                y += lineHeight
            }
            y += spacingAfter
        }

        fun image(bitmap: Bitmap) {
            val maxWidth = pageWidth - 2 * margin
            val maxHeight = 360f
            val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            if (y + h > pageHeight - margin) newPage()
            val left = margin + (maxWidth - w) / 2
            canvas.drawBitmap(bitmap, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 12f
        }

        fun finish() {
            page?.let { doc.finishPage(it) }
            page = null
        }
    }
}
