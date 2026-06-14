package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionTool
import com.lacknb.agentchat.core.network.mcp.McpClient
import org.json.JSONObject

class McpAgentTool(
    private val mcpClient: McpClient,
    private val mcpTool: McpClient.McpTool
) : AgentTool {
    override val name: String = mcpTool.name
    
    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = mcpTool.name,
            description = mcpTool.description,
            parametersJson = mcpTool.inputSchema.toString()
        )
    )
    
    // MCP tools are generally assumed to be low-medium risk unless specified otherwise.
    // For safety, we classify them as Medium.
    override val riskLevel: RiskLevel = RiskLevel.Medium

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return mcpClient.callTool(name, args).toString()
    }
}
