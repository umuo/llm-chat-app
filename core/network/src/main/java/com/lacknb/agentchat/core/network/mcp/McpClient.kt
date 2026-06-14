package com.lacknb.agentchat.core.network.mcp

import android.net.Uri
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class McpClient {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE needs no read timeout
        .build()

    private val eventSourceFactory = EventSources.createFactory(client)
    private var eventSource: EventSource? = null
    private var postEndpoint: String? = null

    // Define data classes for tools
    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JSONObject
    )

    suspend fun connect(mcpUrl: String) {
        postEndpoint = mcpUrl // Under streamable-http, the endpoint is the same URL
        
        // Optionally establish SSE for server-to-client notifications
        val request = Request.Builder()
            .url(mcpUrl)
            .header("Accept", "text/event-stream")
            .build()

        eventSource = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // If the server sends a legacy 'endpoint' event, we can update it just in case
                if (type == "endpoint") {
                    val baseUri = Uri.parse(mcpUrl)
                    postEndpoint = if (data.startsWith("http")) {
                        data
                    } else {
                        Uri.withAppendedPath(baseUri, data).toString()
                    }
                } else if (type == "message") {
                    handleMessage(data)
                }
            }
        })
    }

    private fun handleMessage(data: String) {
        // Handle server-to-client notifications here if needed in the future
        try {
            val json = JSONObject(data)
            // Notifications don't have an ID.
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun sendRpcRequest(method: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val endpoint = postEndpoint ?: throw IllegalStateException("Not connected or endpoint not received")
        
        val id = UUID.randomUUID().toString()
        val rpcRequest = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

        val requestBody = rpcRequest.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        return@withContext suspendCancellableCoroutine { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("HTTP error ${response.code}"))
                            return
                        }
                        
                        val responseBody = response.body?.string()
                        if (responseBody.isNullOrEmpty()) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("Empty response body"))
                            return
                        }
                        
                        val jsonResponse = JSONObject(responseBody)
                        if (continuation.isActive) continuation.resume(jsonResponse)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    suspend fun getTools(): List<McpTool> {
        val response = sendRpcRequest("tools/list")
        if (response.has("error")) {
            throw IOException(response.getJSONObject("error").optString("message", "Unknown RPC error"))
        }
        val result = response.getJSONObject("result")
        val toolsArray = result.getJSONArray("tools")
        val toolsList = mutableListOf<McpTool>()
        for (i in 0 until toolsArray.length()) {
            val toolJson = toolsArray.getJSONObject(i)
            toolsList.add(
                McpTool(
                    name = toolJson.getString("name"),
                    description = toolJson.optString("description", ""),
                    inputSchema = toolJson.optJSONObject("inputSchema") ?: JSONObject()
                )
            )
        }
        return toolsList
    }

    suspend fun callTool(name: String, arguments: JSONObject): JSONObject {
        val params = JSONObject()
            .put("name", name)
            .put("arguments", arguments)

        val response = sendRpcRequest("tools/call", params)
        if (response.has("error")) {
            throw IOException(response.getJSONObject("error").optString("message", "Unknown RPC error"))
        }
        return response.getJSONObject("result")
    }

    fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        postEndpoint = null
    }
}
