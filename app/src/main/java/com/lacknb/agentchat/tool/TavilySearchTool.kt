package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.provider.ProviderRepository
import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionTool
import com.lacknb.agentchat.core.network.TavilyClient
import org.json.JSONObject

class TavilySearchTool(
    private val providerRepository: ProviderRepository
) : AgentTool {
    override val name: String = "tavily_search"
    override val riskLevel: RiskLevel = RiskLevel.Low

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
            description = "联网检索工具。当需要获取最新的网络资料时，必须调用此工具。",
            parametersJson = JSONObject(mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf(
                        "type" to "string",
                        "description" to "搜索关键词。"
                    )
                ),
                "required" to listOf("query")
            )).toString()
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val query = args.optString("query")
        if (query.isNullOrBlank()) {
            return JSONObject().put("ok", false).put("error", "query 不能为空").toString()
        }

        val apiKey = providerRepository.settings.value.tavilyApiKey
        if (apiKey.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Tavily API Key 未配置").toString()
        }

        return try {
            val tavilyClient = TavilyClient()
            val result = tavilyClient.search(apiKey, query)
            
            var resultText = ""
            if (result.context.isNotBlank()) {
                resultText += "【AI Answer】\n${result.context}\n\n"
            }
            if (result.references.isNotEmpty()) {
                resultText += "【Sources】\n"
                result.references.forEachIndexed { index, ref ->
                    resultText += "${index + 1}. ${ref.title} (${ref.url})\n"
                }
            }
            
            if (resultText.isEmpty()) {
                resultText = "未能检索到相关内容。"
            }
            
            JSONObject().put("ok", true).put("result", resultText).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "联网检索失败").toString()
        }
    }
}
