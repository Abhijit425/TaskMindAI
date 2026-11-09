package com.abhijit.airealityscanner.core.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Lightweight Markdown renderer: **bold**, `code`, headings and bullet lists. */
object MarkdownParser {
    private val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

    fun toAnnotated(text: String): AnnotatedString = buildAnnotatedString {
        val lines = text.replace("\r", "").lines()
        lines.forEachIndexed { index, rawLine ->
            var line = rawLine
            var heading = false
            when {
                line.trimStart().startsWith("#") -> {
                    line = line.trimStart().trimStart('#').trim()
                    heading = true
                }
                Regex("^\\s*[-*]\\s+").containsMatchIn(line) -> line = line.replaceFirst(Regex("^\\s*[-*]\\s+"), "•  ")
            }
            if (heading) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(line) }
            } else {
                appendInline(line)
            }
            if (index < lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.appendInline(line: String) {
        var cursor = 0
        inline.findAll(line).forEach { match ->
            append(line.substring(cursor, match.range.first))
            val bold = match.groups[1]?.value
            val code = match.groups[2]?.value
            when {
                bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            }
            cursor = match.range.last + 1
        }
        if (cursor < line.length) append(line.substring(cursor))
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
) {
    val annotated = remember(text) { MarkdownParser.toAnnotated(text) }
    Text(annotated, modifier = modifier, style = style, color = color)
}
