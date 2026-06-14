package com.lacknb.agentchat.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class TavilySearchResult(
    val context: String,
    val references: List<Reference>
) {
    data class Reference(val title: String, val url: String)
}

class TavilyClient {
    private val httpClient: OkHttpClient = OkHttpClient()
    suspend fun search(apiKey: String, query: String): TavilySearchResult = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("search_depth", "basic")
            put("include_answer", true)
            put("include_raw_content", false)
            put("max_results", 5)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw IOException("Tavily API error: ${response.code} $errorBody")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response from Tavily")
        val json = JSONObject(responseBody)
        
        val answer = json.optString("answer", "")
        val resultsArray = json.optJSONArray("results") ?: JSONArray()
        
        val builder = StringBuilder()
        if (answer.isNotEmpty() && answer != "null") {
            builder.append("### Web Search Summary\n$answer\n\n")
        }
        
        val references = mutableListOf<TavilySearchResult.Reference>()
        if (resultsArray.length() > 0) {
            builder.append("### Search Results\n")
            for (i in 0 until resultsArray.length()) {
                val result = resultsArray.optJSONObject(i) ?: continue
                val title = result.optString("title", "Untitled")
                val url = result.optString("url", "")
                val content = result.optString("content", "")
                
                references.add(TavilySearchResult.Reference(title, url))
                builder.append("**$title** ($url)\n")
                builder.append("$content\n\n")
            }
        }
        
        TavilySearchResult(context = builder.toString().trim(), references = references)
    }
}
