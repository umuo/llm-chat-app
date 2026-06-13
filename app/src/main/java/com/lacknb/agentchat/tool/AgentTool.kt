package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionTool

interface AgentTool {
    val name: String
    val declaration: ChatCompletionTool
    val riskLevel: RiskLevel

    suspend fun execute(argumentsJson: String): String
}
