package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionMessage
import com.lacknb.agentchat.core.network.ChatCompletionRequest
import com.lacknb.agentchat.core.network.ChatCompletionStreamEvent
import com.lacknb.agentchat.core.network.ChatCompletionTool
import com.lacknb.agentchat.core.network.ChatCompletionsClient
import com.lacknb.agentchat.core.provider.ProviderRepository
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class PlanAgentTool(
    private val providerRepository: ProviderRepository,
    private val chatClient: ChatCompletionsClient
) : AgentTool {

    override val name: String = "plan_agent"
    override val riskLevel: RiskLevel = RiskLevel.Low

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
            description = "请求规划子智能体为复杂任务创建一个紧凑的计划。作为工具使用，而不是用户可见的模式。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "objective": {"type": "string"},
                    "context": {"type": "string"}
                  },
                  "required": ["objective"]
                }
            """.trimIndent(),
        ),
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val objective = args.optString("objective").ifBlank { args.optString("goal") }
        val context = args.optString("context")
        
        require(objective.isNotBlank()) { "目标（objective）是必需的" }
        
        val profile = providerRepository.currentProfile()
        val apiKey = providerRepository.currentApiKey() ?: error("API 密钥未配置")

        val plan = StringBuilder()
        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = listOf(
                ChatCompletionMessage(
                    role = "system",
                    content = "你是一个规划子智能体。在有用的情况下，生成一个紧凑、可操作的 Markdown 格式的计划，并包含假设和风险。",
                ),
                ChatCompletionMessage(
                    role = "user",
                    content = buildString {
                        appendLine("目标：$objective")
                        if (context.isNotBlank()) appendLine("上下文：$context")
                    },
                ),
            ),
            stream = true,
        )

        withTimeout(30_000) {
            chatClient.streamChatCompletion(
                profile = profile,
                apiKey = apiKey,
                request = request,
            ).collect { event ->
                when (event) {
                    is ChatCompletionStreamEvent.Completed -> Unit
                    is ChatCompletionStreamEvent.Delta -> plan.append(event.content)
                    is ChatCompletionStreamEvent.ToolCallDelta -> Unit
                    is ChatCompletionStreamEvent.Failed -> throw IllegalStateException(event.message, event.cause)
                }
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("plan", plan.toString().take(12_000))
            .toString()
    }
}
