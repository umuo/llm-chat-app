package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionTool
import com.lacknb.agentchat.core.prompts.ManagedPrompt
import com.lacknb.agentchat.core.prompts.PromptRepository
import org.json.JSONArray
import org.json.JSONObject

class ManagePromptsTool(
    private val promptRepository: PromptRepository
) : AgentTool {
    override val name: String = "manage_prompts"
    override val riskLevel: RiskLevel = RiskLevel.Low

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
            description = "管理本机提示词库。支持查询列表、分类、新增、修改、删除、导出 JSON、从 JSON 导入提示词。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["list", "categories", "create", "update", "delete", "export_json", "import_json"]
                    },
                    "id": {"type": "string", "description": "提示词 id。修改或删除时优先使用。"},
                    "title": {"type": "string", "description": "提示词标题；也可用于按精确标题定位。"},
                    "match_title": {"type": "string", "description": "当 title 要作为新标题时，用此字段按旧标题定位。"},
                    "content": {"type": "string", "description": "提示词内容。新增时必填，修改时可选。"},
                    "category": {"type": "string", "description": "提示词分类，查询或导出时可作为筛选条件。"},
                    "query": {"type": "string", "description": "列表查询关键词，会匹配标题、内容和分类。"},
                    "json": {"type": "string", "description": "导入时提供的提示词 JSON。"},
                    "replace_existing": {"type": "boolean", "description": "导入时是否替换现有提示词。默认 false。"}
                  },
                  "required": ["action"]
                }
            """.trimIndent(),
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return when (val action = args.optString("action", "list")) {
            "list" -> {
                val category = args.optStringOrNull("category")
                val query = args.optStringOrNull("query")?.lowercase()
                val prompts = promptRepository.prompts.value
                    .filter { prompt -> category == null || prompt.category == category }
                    .filter { prompt ->
                        query == null ||
                            prompt.title.lowercase().contains(query) ||
                            prompt.content.lowercase().contains(query) ||
                            prompt.category.lowercase().contains(query)
                    }
                JSONObject()
                    .put("ok", true)
                    .put("action", action)
                    .put("prompts", JSONArray().apply { prompts.forEach { put(it.toToolJson(includeContent = true)) } })
                    .toString()
            }
            "categories" -> JSONObject()
                .put("ok", true)
                .put("action", action)
                .put("categories", JSONArray().apply { promptRepository.categories().forEach { put(it) } })
                .toString()
            "create" -> {
                val prompt = promptRepository.createPrompt(
                    title = args.optString("title"),
                    content = args.getString("content"),
                    category = args.optString("category"),
                )
                JSONObject()
                    .put("ok", true)
                    .put("action", action)
                    .put("prompt", prompt.toToolJson(includeContent = true))
                    .toString()
            }
            "update" -> {
                val target = resolvePromptTarget(args)
                val prompt = promptRepository.updatePrompt(
                    id = target.id,
                    title = args.optStringOrNull("title"),
                    content = args.optStringOrNull("content"),
                    category = args.optStringOrNull("category"),
                )
                JSONObject()
                    .put("ok", true)
                    .put("action", action)
                    .put("prompt", prompt.toToolJson(includeContent = true))
                    .toString()
            }
            "delete" -> {
                val target = resolvePromptTarget(args)
                val deleted = promptRepository.deletePrompt(target.id)
                JSONObject()
                    .put("ok", deleted)
                    .put("action", action)
                    .put("deleted_id", target.id)
                    .toString()
            }
            "export_json" -> JSONObject()
                .put("ok", true)
                .put("action", action)
                .put("json", promptRepository.exportToJson(args.optStringOrNull("category")))
                .toString()
            "import_json" -> {
                val imported = promptRepository.importFromJson(
                    json = args.getString("json"),
                    replaceExisting = args.optBoolean("replace_existing", false),
                )
                JSONObject()
                    .put("ok", true)
                    .put("action", action)
                    .put("imported_count", imported.size)
                    .put("prompts", JSONArray().apply { imported.forEach { put(it.toToolJson(includeContent = false)) } })
                    .toString()
            }
            else -> JSONObject()
                .put("ok", false)
                .put("error", "未知提示词操作：$action")
                .toString()
        }
    }

    private fun resolvePromptTarget(args: JSONObject): ManagedPrompt {
        args.optStringOrNull("id")?.let { id ->
            promptRepository.findById(id)?.let { return it }
            error("提示词不存在：$id")
        }
        val title = args.optStringOrNull("title")
            ?: args.optStringOrNull("match_title")
            ?: error("需要提供 id 或 title/match_title")
        val matches = promptRepository.prompts.value.filter { it.title == title }
        require(matches.isNotEmpty()) { "未找到标题为「$title」的提示词" }
        require(matches.size == 1) { "标题「$title」匹配到多条提示词，请改用 id" }
        return matches.first()
    }

    private fun ManagedPrompt.toToolJson(includeContent: Boolean): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("category", category)
            .put("updated_at_millis", updatedAtMillis)
            .apply {
                if (includeContent) put("content", content)
            }
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotBlank() } else null
    }
}
