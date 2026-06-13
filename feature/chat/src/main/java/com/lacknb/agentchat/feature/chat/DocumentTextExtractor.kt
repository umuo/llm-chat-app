package com.lacknb.agentchat.feature.chat

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

object DocumentTextExtractor {

    fun extractTextFromFile(context: Context, uri: Uri, fileName: String): String {
        return try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return "无法打开输入流"
            inputStream.use { stream ->
                val extension = fileName.substringAfterLast(".", "").lowercase()
                when (extension) {
                    "txt", "md", "csv", "json", "xml", "html", "js", "ts", "kt", "java", "py", "sh", "yml", "yaml", "ini", "conf", "sql" -> {
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }
                    "docx" -> {
                        extractTextFromDocx(stream)
                    }
                    "xlsx" -> {
                        extractTextFromXlsx(stream)
                    }
                    else -> {
                        // Fallback: attempt to read as plain text, if binary/error, return friendly message
                        try {
                            val text = stream.bufferedReader(Charsets.UTF_8).readText()
                            // Simple heuristic to check if it's binary content (null bytes)
                            if (text.contains('\u0000')) {
                                "无法提取该文件格式的内容，请尝试上传 .txt, .docx, .xlsx, .csv 等格式文件。"
                            } else {
                                text
                            }
                        } catch (e: Exception) {
                            "不支持的文件格式：无法读取文件内容。"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "文件解析出错: ${e.localizedMessage}"
        }
    }

    private fun extractTextFromDocx(inputStream: InputStream): String {
        return try {
            val zip = ZipInputStream(inputStream)
            var entry = zip.nextEntry
            var text = ""
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    text = parseDocxXml(zip)
                    break
                }
                entry = zip.nextEntry
            }
            text
        } catch (e: Exception) {
            e.printStackTrace()
            "Docx解析出错: ${e.localizedMessage}"
        }
    }

    private fun parseDocxXml(inputStream: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        val builder = java.lang.StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "t") {
                builder.append(parser.nextText())
            } else if (eventType == XmlPullParser.END_TAG && parser.name == "p") {
                builder.append("\n")
            }
            eventType = parser.next()
        }
        return builder.toString()
    }

    private fun extractTextFromXlsx(inputStream: InputStream): String {
        return try {
            val zip = ZipInputStream(inputStream)
            var entry = zip.nextEntry
            val sharedStrings = mutableListOf<String>()
            val bytesMap = mutableMapOf<String, ByteArray>()

            while (entry != null) {
                if (entry.name == "xl/sharedStrings.xml" || (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml"))) {
                    bytesMap[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }

            val sharedStringsBytes = bytesMap["xl/sharedStrings.xml"]
            if (sharedStringsBytes != null) {
                sharedStrings.addAll(parseSharedStrings(sharedStringsBytes.inputStream()))
            }

            val builder = java.lang.StringBuilder()
            bytesMap.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted().forEach { sheetKey ->
                val sheetBytes = bytesMap[sheetKey] ?: return@forEach
                val sheetNumber = sheetKey.substringAfterLast("sheet").substringBefore(".xml")
                builder.appendLine("--- 工作表 (Sheet $sheetNumber) ---")
                builder.append(parseXlsxSheet(sheetBytes.inputStream(), sharedStrings))
                builder.appendLine()
            }
            builder.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            "Xlsx解析出错: ${e.localizedMessage}"
        }
    }

    private fun parseSharedStrings(inputStream: InputStream): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        val list = mutableListOf<String>()
        var eventType = parser.eventType
        var inText = false
        val textBuilder = java.lang.StringBuilder()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "t") {
                    inText = true
                    textBuilder.setLength(0)
                }
            } else if (eventType == XmlPullParser.TEXT && inText) {
                textBuilder.append(parser.text)
            } else if (eventType == XmlPullParser.END_TAG) {
                if (parser.name == "t") {
                    inText = false
                    list.add(textBuilder.toString())
                }
            }
            eventType = parser.next()
        }
        return list
    }

    private fun parseXlsxSheet(inputStream: InputStream, sharedStrings: List<String>): String {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        val builder = java.lang.StringBuilder()
        var eventType = parser.eventType
        var isSharedString = false
        var inValue = false
        var rowStarted = false
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "row") {
                    if (rowStarted) builder.append("\n")
                    rowStarted = true
                } else if (parser.name == "c") {
                    val t = parser.getAttributeValue(null, "t")
                    isSharedString = (t == "s")
                } else if (parser.name == "v") {
                    inValue = true
                }
            } else if (eventType == XmlPullParser.TEXT && inValue) {
                val rawValue = parser.text
                val displayValue = if (isSharedString) {
                    val idx = rawValue.toIntOrNull()
                    if (idx != null && idx >= 0 && idx < sharedStrings.size) {
                        sharedStrings[idx]
                     } else {
                        rawValue
                     }
                } else {
                    rawValue
                }
                builder.append(displayValue).append("\t")
            } else if (eventType == XmlPullParser.END_TAG) {
                if (parser.name == "v") {
                    inValue = false
                } else if (parser.name == "row") {
                    builder.append("\n")
                    rowStarted = false
                }
            }
            eventType = parser.next()
        }
        return builder.toString()
    }
}
