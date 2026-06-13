package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionTool
import org.json.JSONObject

class RecordMemoryCandidateTool : AgentTool {
    override val name: String = "record_memory_candidate"
    override val riskLevel: RiskLevel = RiskLevel.Low

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
            description = "提议一条记忆项，在保存前应展示给用户以进行审批。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "content": {"type": "string"},
                    "reason": {"type": "string"},
                    "sensitivity": {"type": "string", "enum": ["low", "medium", "high"]}
                  },
                  "required": ["content", "reason", "sensitivity"]
                }
            """.trimIndent(),
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return JSONObject()
            .put("ok", true)
            .put("status", "candidate_recorded_for_review")
            .put("content", args.optString("content"))
            .put("reason", args.optString("reason"))
            .put("sensitivity", args.optString("sensitivity"))
            .toString()
    }
}
