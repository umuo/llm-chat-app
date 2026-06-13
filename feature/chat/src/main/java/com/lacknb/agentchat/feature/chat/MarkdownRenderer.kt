package com.lacknb.agentchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

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
                is MarkdownBlock.Code -> CodeBlock(code = block.code)
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
    modifier: Modifier = Modifier,
) {
    Text(
        text = code.ifBlank { " " },
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val code: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
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
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
                code.clear()
            } else {
                flushParagraph()
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
        blocks += MarkdownBlock.Code(code.toString().trimEnd())
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
