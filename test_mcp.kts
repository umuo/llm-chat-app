import java.net.URL
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

val requestBody = """{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}""".toRequestBody("application/json".toMediaType())

val request = Request.Builder()
    .url("http://192.168.0.220:3000/mcp")
    .header("Accept", "application/json, text/event-stream")
    .post(requestBody)
    .build()

println("Sending request...")
val response = client.newCall(request).execute()
println("Response code: ${response.code}")
println("Response body: ${response.body?.string()}")
