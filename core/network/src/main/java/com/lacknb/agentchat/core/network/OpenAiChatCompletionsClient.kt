package com.lacknb.agentchat.core.network

import android.util.Log
import com.lacknb.agentchat.core.model.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val NetworkLogTag = "AgentChatNetwork"

class OpenAiChatCompletionsClient : ChatCompletionsClient {

    override suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        timeoutSeconds: Int,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = baseUrl.trim().trimEnd('/') + "/models"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutSeconds * 1000
                readTimeout = timeoutSeconds * 1000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }

            try {
                if (connection.responseCode !in 200..299) {
                    val message = buildHttpErrorMessage(connection)
                    Log.w(NetworkLogTag, message)
                    throw ModelListException(message)
                }

                val responseBody = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val models = JSONObject(responseBody).toModelIds()
                require(models.isNotEmpty()) {
                    "模型列表为空"
                }
                models
            } catch (error: ModelListException) {
                throw error
            } catch (error: Throwable) {
                val message = buildRequestFailureMessage("Models request failed", error)
                Log.e(NetworkLogTag, message, error)
                throw IllegalStateException(message, error)
            } finally {
                connection.disconnect()
            }
        }
    }

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
                val message = buildHttpErrorMessage(connection)
                Log.w(NetworkLogTag, message)
                emit(ChatCompletionStreamEvent.Failed(message))
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
            val message = buildRequestFailureMessage("Chat Completions request failed", error)
            Log.e(NetworkLogTag, message, error)
            emit(ChatCompletionStreamEvent.Failed(message, error))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

private class ModelListException(message: String) : RuntimeException(message)

private fun buildHttpErrorMessage(connection: HttpURLConnection): String {
    val statusLine = "HTTP ${connection.responseCode}: ${connection.responseMessage}"
    val responseBody = runCatching {
        connection.errorStream
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?.trim()
            ?.take(1_200)
    }.getOrNull()

    return if (responseBody.isNullOrBlank()) {
        statusLine
    } else {
        "$statusLine\n$responseBody"
    }
}

private fun buildRequestFailureMessage(prefix: String, error: Throwable): String {
    val type = error::class.java.simpleName.ifBlank { "Throwable" }
    val detail = error.message?.takeIf { it.isNotBlank() }
    return if (detail == null) {
        "$prefix: $type"
    } else {
        "$prefix: $type: $detail"
    }
}

private fun JSONObject.toModelIds(): List<String> {
    val data = optJSONArray("data") ?: JSONArray()
    return buildList {
        for (index in 0 until data.length()) {
            val model = data.optJSONObject(index) ?: continue
            val id = model.optString("id").trim()
            if (id.isNotEmpty()) add(id)
        }
    }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
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
                            if (message.imageUrls.isEmpty()) {
                                put("content", message.content.orEmpty())
                            } else {
                                val contentArray = JSONArray().apply {
                                    put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", message.content.orEmpty())
                                    )
                                    message.imageUrls.forEach { imageUrl ->
                                        put(
                                            JSONObject()
                                                .put("type", "image_url")
                                                .put(
                                                    "image_url",
                                                    JSONObject().put("url", imageUrl)
                                                )
                                        )
                                    }
                                }
                                put("content", contentArray)
                            }
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
