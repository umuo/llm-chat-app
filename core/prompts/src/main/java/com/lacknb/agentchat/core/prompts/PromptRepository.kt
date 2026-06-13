package com.lacknb.agentchat.core.prompts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ManagedPrompt(
    val id: String,
    val title: String,
    val content: String,
    val category: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

class PromptRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val _prompts = MutableStateFlow(loadPrompts())

    val prompts: StateFlow<List<ManagedPrompt>> = _prompts.asStateFlow()

    fun createPrompt(
        title: String,
        content: String,
        category: String,
    ): ManagedPrompt {
        val now = System.currentTimeMillis()
        val prompt = ManagedPrompt(
            id = newPromptId(),
            title = title.normalizedTitle(content),
            content = content.trim(),
            category = category.normalizedCategory(),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        require(prompt.content.isNotBlank()) { "提示词内容不能为空" }
        savePrompts((_prompts.value + prompt).sortedForDisplay())
        return prompt
    }

    fun updatePrompt(
        id: String,
        title: String?,
        content: String?,
        category: String?,
    ): ManagedPrompt {
        val current = _prompts.value
        val index = current.indexOfFirst { it.id == id }
        require(index >= 0) { "提示词不存在：$id" }
        val original = current[index]
        val updatedContent = content?.trim() ?: original.content
        val updated = original.copy(
            title = title?.normalizedTitle(updatedContent) ?: original.title,
            content = updatedContent,
            category = category?.normalizedCategory() ?: original.category,
            updatedAtMillis = System.currentTimeMillis(),
        )
        require(updated.content.isNotBlank()) { "提示词内容不能为空" }
        savePrompts(current.toMutableList().also { it[index] = updated }.sortedForDisplay())
        return updated
    }

    fun deletePrompt(id: String): Boolean {
        val updated = _prompts.value.filterNot { it.id == id }
        val changed = updated.size != _prompts.value.size
        if (changed) savePrompts(updated)
        return changed
    }

    fun importFromJson(json: String, replaceExisting: Boolean = false): List<ManagedPrompt> {
        val imported = parsePromptExport(json)
        val merged = if (replaceExisting) {
            imported
        } else {
            val existingById = _prompts.value.associateBy { it.id }.toMutableMap()
            imported.forEach { prompt -> existingById[prompt.id] = prompt }
            existingById.values.toList()
        }
        savePrompts(merged.sortedForDisplay())
        return imported
    }

    fun exportToJson(category: String? = null): String {
        val normalizedCategory = category?.trim()?.takeIf { it.isNotBlank() && it != AllCategories }
        val exportPrompts = _prompts.value
            .filter { prompt -> normalizedCategory == null || prompt.category == normalizedCategory }
            .sortedForDisplay()
        return JSONObject()
            .put("schema_version", 1)
            .put("exported_at_millis", System.currentTimeMillis())
            .put("prompts", JSONArray().apply {
                exportPrompts.forEach { put(it.toJson()) }
            })
            .toString(2)
    }

    fun categories(): List<String> {
        return _prompts.value.map { it.category }.distinct().sorted()
    }

    fun findById(id: String): ManagedPrompt? = _prompts.value.firstOrNull { it.id == id }

    private fun loadPrompts(): List<ManagedPrompt> {
        val stored = prefs.getString(KeyPromptsJson, null) ?: return emptyList()
        return runCatching { parsePromptExport(stored).sortedForDisplay() }.getOrDefault(emptyList())
    }

    private fun savePrompts(prompts: List<ManagedPrompt>) {
        prefs.edit()
            .putString(
                KeyPromptsJson,
                JSONObject()
                    .put("schema_version", 1)
                    .put("prompts", JSONArray().apply { prompts.forEach { put(it.toJson()) } })
                    .toString(),
            )
            .apply()
        _prompts.value = prompts
    }

    private fun parsePromptExport(json: String): List<ManagedPrompt> {
        val trimmed = json.trim()
        require(trimmed.isNotBlank()) { "导入内容不能为空" }
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).getJSONArray("prompts")
        }
        val now = System.currentTimeMillis()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val content = item.optString("content").trim()
                if (content.isBlank()) continue
                val title = item.optString("title").normalizedTitle(content)
                add(
                    ManagedPrompt(
                        id = item.optString("id").takeIf { it.isNotBlank() } ?: newPromptId(index),
                        title = title,
                        content = content,
                        category = item.optString("category").normalizedCategory(),
                        createdAtMillis = item.optLong("created_at_millis", now),
                        updatedAtMillis = item.optLong("updated_at_millis", now),
                    ),
                )
            }
        }
    }

    private fun ManagedPrompt.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("content", content)
            .put("category", category)
            .put("created_at_millis", createdAtMillis)
            .put("updated_at_millis", updatedAtMillis)
    }

    private fun String.normalizedTitle(content: String): String {
        val title = trim()
        if (title.isNotBlank()) return title
        return content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(32) ?: "未命名提示词"
    }

    private fun String.normalizedCategory(): String {
        return trim().ifBlank { DefaultCategory }
    }

    private fun List<ManagedPrompt>.sortedForDisplay(): List<ManagedPrompt> {
        return sortedWith(compareBy<ManagedPrompt> { it.category }.thenByDescending { it.updatedAtMillis })
    }

    private fun newPromptId(suffix: Int? = null): String {
        return buildString {
            append("prompt-")
            append(System.currentTimeMillis())
            append("-")
            append((1000..9999).random())
            if (suffix != null) {
                append("-")
                append(suffix)
            }
        }
    }

    companion object {
        const val AllCategories = "全部"
        const val DefaultCategory = "未分类"
        private const val PrefsName = "agentchat_prompts"
        private const val KeyPromptsJson = "prompts_json"
    }
}
