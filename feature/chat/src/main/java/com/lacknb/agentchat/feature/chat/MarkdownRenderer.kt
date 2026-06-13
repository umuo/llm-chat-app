package com.lacknb.agentchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MarkdownContent(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(code = block.code, language = block.language)
                is MarkdownBlock.Heading -> Text(
                    text = block.text,
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, color),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MarkdownBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = color, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = inlineMarkdown(block.text, color),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MarkdownBlock.Quote -> Text(
                    text = inlineMarkdown(block.text, color.copy(alpha = 0.82f)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                )
            }
        }
    }
}

@Composable
internal fun CodeBlock(
    code: String,
    language: String? = null,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val highlightedText = remember(code, isDark) {
        highlightCode(code, isDark)
    }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = language?.uppercase() ?: "CODE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (isCopied) "已复制" else "复制",
                style = MaterialTheme.typography.labelMedium,
                color = if (isCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        clipboardManager.setText(AnnotatedString(code))
                        isCopied = true
                        scope.launch {
                            delay(2000)
                            isCopied = false
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = highlightedText,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}

private fun highlightCode(code: String, isDark: Boolean): AnnotatedString = buildAnnotatedString {
    val commentColor = if (isDark) Color(0xFF7A8A7B) else Color(0xFF6A7A6B)
    val stringColor = if (isDark) Color(0xFF8BC34A) else Color(0xFF43A047)
    val annotationColor = if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100)
    val keywordColor = if (isDark) Color(0xFFE91E63) else Color(0xFFC2185B)
    val typeColor = if (isDark) Color(0xFF4FC3F7) else Color(0xFF0288D1)
    val numberColor = if (isDark) Color(0xFF80DEEA) else Color(0xFF00ACC1)
    val normalColor = if (isDark) Color(0xFFE2E3DE) else Color(0xFF1A1C1A)

    val pattern = Regex(
        "(?<comment>//.*|/\\*[\\s\\S]*?\\*/|#.*)" +
        "|(?<string>\"\"\"[\\s\\S]*?\"\"\"|\"[^\"]*\"|'[^']*')" +
        "|(?<annotation>@[a-zA-Z0-9_]+)" +
        "|(?<keyword>\\b(class|interface|object|enum|fun|val|var|package|import|return|if|else|for|while|when|in|is|as|try|catch|finally|throw|def|const|let|function|public|private|protected|internal|override|null|true|false|void|int|double|float|long|boolean|char|struct|impl|fn|let|mut|pub|use|mod|struct|where|type|as)\\b)" +
        "|(?<type>\\b[A-Z][a-zA-Z0-9_]*\\b)" +
        "|(?<number>\\b\\d+(\\.\\d+)?([fFLl])?\\b)"
    )

    var lastIndex = 0
    pattern.findAll(code).forEach { match ->
        if (match.range.first > lastIndex) {
            withStyle(SpanStyle(color = normalColor)) {
                append(code.substring(lastIndex, match.range.first))
            }
        }

        val style = when {
            match.groups["comment"] != null -> SpanStyle(color = commentColor, fontFamily = FontFamily.Monospace)
            match.groups["string"] != null -> SpanStyle(color = stringColor, fontFamily = FontFamily.Monospace)
            match.groups["annotation"] != null -> SpanStyle(color = annotationColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            match.groups["keyword"] != null -> SpanStyle(color = keywordColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            match.groups["type"] != null -> SpanStyle(color = typeColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            match.groups["number"] != null -> SpanStyle(color = numberColor, fontFamily = FontFamily.Monospace)
            else -> SpanStyle(color = normalColor)
        }

        withStyle(style) {
            append(match.value)
        }
        lastIndex = match.range.last + 1
    }

    if (lastIndex < code.length) {
        withStyle(SpanStyle(color = normalColor)) {
            append(code.substring(lastIndex))
        }
    }
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var currentLanguage: String? = null
    var inCode = false

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.clear()
    }

    markdown.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MarkdownBlock.Code(currentLanguage, code.toString().trimEnd())
                code.clear()
                currentLanguage = null
            } else {
                flushParagraph()
                currentLanguage = line.trimStart().removePrefix("```").trim().lowercase().takeIf { it.isNotEmpty() }
            }
            inCode = !inCode
            return@forEach
        }

        if (inCode) {
            code.appendLine(rawLine)
            return@forEach
        }

        val trimmed = line.trim()
        when {
            trimmed.isBlank() -> flushParagraph()
            trimmed.startsWith("### ") -> {
                flushParagraph()
                val (heading, firstBullet) = splitHeadingAndInlineBullet(trimmed.removePrefix("### ").trim())
                blocks += MarkdownBlock.Heading(level = 3, text = heading)
                firstBullet?.let { blocks += MarkdownBlock.Bullet(it) }
            }
            trimmed.startsWith("## ") -> {
                flushParagraph()
                val (heading, firstBullet) = splitHeadingAndInlineBullet(trimmed.removePrefix("## ").trim())
                blocks += MarkdownBlock.Heading(level = 2, text = heading)
                firstBullet?.let { blocks += MarkdownBlock.Bullet(it) }
            }
            trimmed.startsWith("# ") -> {
                flushParagraph()
                val (heading, firstBullet) = splitHeadingAndInlineBullet(trimmed.removePrefix("# ").trim())
                blocks += MarkdownBlock.Heading(level = 1, text = heading)
                firstBullet?.let { blocks += MarkdownBlock.Bullet(it) }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(trimmed.drop(2).trim())
            }
            trimmed.matches(Regex("""\d+\.\s+.*""")) -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(trimmed.substringAfter(".").trim())
            }
            trimmed.startsWith("> ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix("> ").trim())
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
    }

    if (inCode) {
        blocks += MarkdownBlock.Code(currentLanguage, code.toString().trimEnd())
    }
    flushParagraph()
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(markdown)) }
}

private fun splitHeadingAndInlineBullet(text: String): Pair<String, String?> {
    val match = Regex("""^(.+?)\s*[-*]\s+(.+)$""").matchEntire(text)
    return if (match != null) {
        match.groupValues[1].trim() to match.groupValues[2].trim()
    } else {
        text.trim() to null
    }
}

private fun inlineMarkdown(
    text: String,
    color: Color,
) = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index) {
                    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index) {
                    withStyle(SpanStyle(color = color, fontFamily = FontFamily.Monospace)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                withStyle(SpanStyle(color = color)) {
                    append(text[index])
                }
                index++
            }
        }
    }
}
