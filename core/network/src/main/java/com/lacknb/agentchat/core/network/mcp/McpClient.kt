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

    private val pendingRequests = ConcurrentHashMap<String, CancellableContinuation<JSONObject>>()

    // Define data classes for tools
    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JSONObject
    )

    suspend fun connect(sseUrl: String) {
        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(sseUrl)
                .header("Accept", "text/event-stream")
                .build()

            eventSource = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    // Connection opened, but we wait for the 'endpoint' event
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (type == "endpoint") {
                        val baseUri = Uri.parse(sseUrl)
                        // data usually contains a relative or absolute path
                        val endpointUri = if (data.startsWith("http")) {
                            data
                        } else {
                            Uri.withAppendedPath(baseUri, data).toString()
                        }
                        postEndpoint = endpointUri
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    } else if (type == "message") {
                        handleMessage(data)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("SSE closed unexpectedly"))
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (continuation.isActive) {
                        val ex = t ?: IOException("SSE connection failed with code ${response?.code}")
                        continuation.resumeWithException(ex)
                    }
                }
            })

            continuation.invokeOnCancellation {
                eventSource?.cancel()
            }
        }
    }

    private fun handleMessage(data: String) {
        try {
            val json = JSONObject(data)
            val id = json.optString("id", "")
            if (id.isNotEmpty() && pendingRequests.containsKey(id)) {
                pendingRequests.remove(id)?.resume(json)
            }
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
            pendingRequests[id] = continuation

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    pendingRequests.remove(id)
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        pendingRequests.remove(id)
                        if (continuation.isActive) continuation.resumeWithException(IOException("HTTP error ${response.code}"))
                    }
                    // Actual response comes via SSE message
                    response.close()
                }
            })

            continuation.invokeOnCancellation {
                pendingRequests.remove(id)
            }
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
        pendingRequests.forEach { (_, cont) -> cont.cancel() }
        pendingRequests.clear()
    }
}
