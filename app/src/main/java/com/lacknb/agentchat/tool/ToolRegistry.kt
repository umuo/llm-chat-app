package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionTool
import org.json.JSONObject

class ToolRegistry(baseTools: List<AgentTool>) {

    private val _tools = mutableListOf<AgentTool>().apply { addAll(baseTools) }

    val size: Int
        get() = _tools.size

    fun getAllDeclarations(): List<ChatCompletionTool> = _tools.map { it.declaration }

    fun addDynamicTools(dynamicTools: List<AgentTool>) {
        // Remove existing dynamic tools (assuming they are McpAgentTool for now, or just clearing them)
        _tools.removeAll { it is McpAgentTool }
        _tools.addAll(dynamicTools)
    }

    fun clearDynamicTools() {
        _tools.removeAll { it is McpAgentTool }
    }

    fun getRiskLevel(name: String?): RiskLevel =
        _tools.find { it.name == name }?.riskLevel ?: RiskLevel.Low

    suspend fun execute(name: String, argumentsJson: String): String {
        val tool = _tools.find { it.name == name }
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
