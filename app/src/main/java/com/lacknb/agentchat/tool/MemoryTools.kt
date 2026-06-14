package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.memory.MemoryItem
import com.lacknb.agentchat.core.memory.MemoryRepository
import com.lacknb.agentchat.core.memory.MemorySensitivity
import com.lacknb.agentchat.core.memory.MemorySource
import com.lacknb.agentchat.core.memory.MemoryType
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionTool
import org.json.JSONArray
import org.json.JSONObject

class ManageMemoryTool(
    private val memoryRepository: MemoryRepository,
) : AgentTool {
    override val name: String = "manage_memory"
    override val riskLevel: RiskLevel = RiskLevel.Low

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
            description = "管理本机通用记忆库。可保存用户明确要求记住的信息，也可查询用户之前保存的笔记、偏好和事实。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["create", "search", "list", "delete"]
                    },
                    "id": {"type": "string", "description": "记忆 id，删除时必填。"},
                    "title": {"type": "string", "description": "记忆标题。"},
                    "content": {"type": "string", "description": "记忆正文。新增时必填。如果内容是网址或包含网址，请优先同时保留 Markdown 链接和原始网址，例如 [博客](https://example.com)\\n原始链接：https://example.com。"},
                    "type": {
                      "type": "string",
                      "enum": ["note", "preference", "fact"],
                      "description": "记忆类型。普通信息用 note，用户偏好用 preference，稳定事实用 fact。"
                    },
                    "tags": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "标签列表，例如 blog、personal、android。"
                    },
                    "sensitivity": {
                      "type": "string",
                      "enum": ["low", "medium", "high"],
                      "description": "敏感级别。普通公开信息用 low。"
                    },
                    "query": {"type": "string", "description": "搜索关键词。"},
                    "limit": {"type": "integer", "description": "最多返回多少条，默认 10。"}
                  },
                  "required": ["action"]
                }
            """.trimIndent(),
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return when (val action = args.optString("action", "search")) {
            "create" -> createMemory(args, action)
            "search", "list" -> searchMemories(args, action)
            "delete" -> deleteMemory(args, action)
            else -> JSONObject()
                .put("ok", false)
                .put("error", "未知记忆操作：$action")
                .toString()
        }
    }

    private suspend fun createMemory(args: JSONObject, action: String): String {
        val content = args.getString("content")
        val item = memoryRepository.createMemory(
            title = args.optString("title"),
            content = content,
            type = args.optStringOrNull("type").toMemoryType(),
            tags = args.optStringArray("tags"),
            sensitivity = args.optStringOrNull("sensitivity").toSensitivity(),
            source = MemorySource.Agent,
            confidence = 1f,
        )
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("memory", item.toToolJson())
            .toString()
    }

    private suspend fun searchMemories(args: JSONObject, action: String): String {
        val query = args.optString("query")
        val type = args.optStringOrNull("type")?.toMemoryType()
        val limit = args.optInt("limit", 10)
        val typedMemories = memoryRepository.searchMemories(
            query = query,
            type = type,
            limit = limit,
        )
        val memories = if (typedMemories.isEmpty() && type != null) {
            memoryRepository.searchMemories(
                query = query,
                type = null,
                limit = limit,
            )
        } else {
            typedMemories
        }
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("count", memories.size)
            .put("relaxed_type_filter", typedMemories.isEmpty() && type != null)
            .put("memories", JSONArray().apply { memories.forEach { put(it.toToolJson()) } })
            .toString()
    }

    private suspend fun deleteMemory(args: JSONObject, action: String): String {
        val id = args.getString("id")
        memoryRepository.deleteMemory(id)
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("deleted_id", id)
            .toString()
    }

    private fun MemoryItem.toToolJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("content", content)
            .put("type", type.name.lowercase())
            .put("tags", JSONArray().apply { tags.forEach { put(it) } })
            .put("sensitivity", sensitivity.name.lowercase())
            .put("updated_at_millis", updatedAtMillis)
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotBlank() } else null
    }

    private fun JSONObject.optStringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun String?.toMemoryType(): MemoryType {
        return when (this?.lowercase()) {
            "url", "link", "website", "note" -> MemoryType.Note
            "preference", "pref" -> MemoryType.Preference
            "fact" -> MemoryType.Fact
            else -> MemoryType.Note
        }
    }

    private fun String?.toSensitivity(): MemorySensitivity {
        return when (this?.lowercase()) {
            "medium" -> MemorySensitivity.Medium
            "high" -> MemorySensitivity.High
            else -> MemorySensitivity.Low
        }
    }
}
