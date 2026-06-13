package com.lacknb.agentchat.core.network

import com.lacknb.agentchat.core.model.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAiChatCompletionsClient : ChatCompletionsClient {

    override fun streamChatCompletion(
        profile: ProviderProfile,
        apiKey: String,
        request: ChatCompletionRequest,
    ): Flow<ChatCompletionStreamEvent> = flow {
        val endpoint = profile.baseUrl.trimEnd('/') + "/chat/completions"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = profile.timeoutSeconds * 1000
            readTimeout = profile.timeoutSeconds * 1000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(request.toJson().toString())
            }

            if (connection.responseCode !in 200..299) {
                emit(ChatCompletionStreamEvent.Failed("HTTP ${connection.responseCode}: ${connection.responseMessage}"))
                return@flow
            }

            BufferedReader(connection.inputStream.reader(Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        emit(ChatCompletionStreamEvent.Completed(usage = null))
                        break
                    }

                    val chunk = runCatching {
                        JSONObject(payload).toChatCompletionChunk()
                    }.getOrElse { error ->
                        emit(ChatCompletionStreamEvent.Failed("Unable to parse stream chunk", error))
                        return@flow
                    }

                    chunk.choices
                        .mapNotNull { it.delta?.content }
                        .filter { it.isNotEmpty() }
                        .forEach { emit(ChatCompletionStreamEvent.Delta(it)) }

                    chunk.choices
                        .flatMap { it.delta?.toolCalls.orEmpty() }
                        .forEach { emit(ChatCompletionStreamEvent.ToolCallDelta(it)) }
                }
            }
        } catch (error: Throwable) {
            emit(ChatCompletionStreamEvent.Failed("Chat Completions request failed", error))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

private fun ChatCompletionRequest.toJson(): JSONObject {
    val messagesJson = JSONArray().apply {
        messages.forEach { message ->
            put(
                JSONObject()
                    .put("role", message.role)
                    .apply {
                        message.toolCallId?.let { put("tool_call_id", it) }
                        if (message.toolCalls.isNotEmpty()) {
                            put(
                                "tool_calls",
                                JSONArray().apply {
                                    message.toolCalls.forEach { toolCall ->
                                        put(
                                            JSONObject()
                                                .put("id", toolCall.id)
                                                .put("type", toolCall.type)
                                                .put(
                                                    "function",
                                                    JSONObject()
                                                        .put("name", toolCall.function.name)
                                                        .put("arguments", toolCall.function.arguments),
                                                ),
                                        )
                                    }
                                },
                            )
                        }
                        if (message.content != null || message.toolCalls.isEmpty()) {
                            put("content", message.content.orEmpty())
                        }
                    },
            )
        }
    }

    return JSONObject()
        .put("model", model)
        .put("messages", messagesJson)
        .put("stream", stream)
        .apply {
            temperature?.let { put("temperature", it) }
            topP?.let { put("top_p", it) }
            maxTokens?.let { put("max_tokens", it) }
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JSONArray().apply {
                        tools.forEach { tool ->
                            put(
                                JSONObject()
                                    .put("type", tool.type)
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", tool.function.name)
                                            .put("description", tool.function.description)
                                            .put("parameters", JSONObject(tool.function.parametersJson)),
                                    ),
                            )
                        }
                    },
                )
            }
            toolChoice?.let { put("tool_choice", it) }
        }
}

private fun JSONObject.toChatCompletionChunk(): ChatCompletionChunk {
    val choicesJson = optJSONArray("choices") ?: JSONArray()
    val choices = buildList {
        for (index in 0 until choicesJson.length()) {
            val choiceJson = choicesJson.optJSONObject(index) ?: continue
            add(
                ChatCompletionChoice(
                    delta = choiceJson.optJSONObject("delta")?.toChatCompletionDelta(),
                    finishReason = choiceJson.optString("finish_reason").takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    return ChatCompletionChunk(
        id = optString("id").takeIf { it.isNotBlank() },
        choices = choices,
    )
}

private fun JSONObject.toChatCompletionDelta(): ChatCompletionDelta {
    return ChatCompletionDelta(
        role = optString("role").takeIf { it.isNotBlank() },
        content = optString("content").takeIf { it.isNotBlank() },
        toolCalls = optJSONArray("tool_calls")?.toToolCallDeltas().orEmpty(),
    )
}

private fun JSONArray.toToolCallDeltas(): List<ChatCompletionToolCallDelta> {
    return buildList {
        for (index in 0 until length()) {
            val toolJson = optJSONObject(index) ?: continue
            val functionJson = toolJson.optJSONObject("function")
            add(
                ChatCompletionToolCallDelta(
                    index = toolJson.optInt("index", index),
                    id = toolJson.optString("id").takeIf { it.isNotBlank() },
                    type = toolJson.optString("type").takeIf { it.isNotBlank() },
                    functionName = functionJson?.optString("name")?.takeIf { it.isNotBlank() },
                    arguments = functionJson?.optString("arguments").orEmpty(),
                ),
            )
        }
    }
}
