package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionTool
import org.json.JSONObject

class ToolRegistry(private val tools: List<AgentTool>) {

    val size: Int
        get() = tools.size

    fun getAllDeclarations(): List<ChatCompletionTool> = tools.map { it.declaration }

    fun getRiskLevel(name: String?): RiskLevel =
        tools.find { it.name == name }?.riskLevel ?: RiskLevel.Low

    suspend fun execute(name: String, argumentsJson: String): String {
        val tool = tools.find { it.name == name }
        return if (tool != null) {
            runCatching {
                tool.execute(argumentsJson)
            }.getOrElse { error ->
                JSONObject()
                    .put("ok", false)
                    .put("error", error.message ?: "工具执行失败")
                    .toString()
            }
        } else {
            JSONObject()
                .put("ok", false)
                .put("error", "未知工具：$name")
                .toString()
        }
    }
}
