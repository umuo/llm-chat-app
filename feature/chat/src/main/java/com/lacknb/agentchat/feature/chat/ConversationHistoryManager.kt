package com.lacknb.agentchat.feature.chat

import android.content.Context
import com.lacknb.agentchat.core.harness.AgentEvent
import com.lacknb.agentchat.core.harness.AgentEventType
import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.model.ChatAttachment
import com.lacknb.agentchat.core.model.ChatMessage
import com.lacknb.agentchat.core.model.ChatMode
import com.lacknb.agentchat.core.model.ChatToolCall
import com.lacknb.agentchat.core.model.MessageRole
import com.lacknb.agentchat.core.model.MessageStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ConversationSummary(
    val id: String,
    val title: String,
    val timestamp: Long,
    val selectedMode: ChatMode,
)

data class ConversationDetail(
    val id: String,
    val title: String,
    val timestamp: Long,
    val selectedMode: ChatMode,
    val messages: List<ChatMessage>,
    val agentEvents: Map<String, List<AgentEvent>>,
)

class ConversationHistoryManager(private val context: Context) {
    private val historyDir = File(context.filesDir, "history").apply {
        if (!exists()) mkdirs()
    }
    private val summaryFile = File(historyDir, "conversations.json")

    fun getSummaries(): List<ConversationSummary> {
        if (!summaryFile.exists()) return emptyList()
        return try {
            val jsonString = summaryFile.readText()
            val jsonArray = JSONArray(jsonString)
            val summaries = mutableListOf<ConversationSummary>()
            for (i in 0 until jsonArray.length()) {
                summaries.add(jsonArray.getJSONObject(i).toConversationSummary())
            }
            summaries.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveConversation(
        id: String?,
        mode: ChatMode,
        messages: List<ChatMessage>,
        agentEvents: Map<String, List<AgentEvent>>
    ): String {
        if (messages.isEmpty()) return id ?: UUID.randomUUID().toString()

        val actualId = id ?: UUID.randomUUID().toString()
        val firstUserMessage = messages.firstOrNull { it.role == MessageRole.User }?.content
        val title = if (!firstUserMessage.isNullOrBlank()) {
            if (firstUserMessage.length > 30) firstUserMessage.take(30) + "..." else firstUserMessage
        } else {
            "新对话"
        }
        val timestamp = System.currentTimeMillis()

        val detail = ConversationDetail(actualId, title, timestamp, mode, messages, agentEvents)
        val detailFile = File(historyDir, "$actualId.json")
        try {
            detailFile.writeText(detail.toJson().toString())

            // Update summaries list
            val summaries = getSummaries().toMutableList()
            val existingIndex = summaries.indexOfFirst { it.id == actualId }
            val newSummary = ConversationSummary(actualId, title, timestamp, mode)
            if (existingIndex >= 0) {
                summaries[existingIndex] = newSummary
            } else {
                summaries.add(0, newSummary)
            }
            saveSummaries(summaries)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return actualId
    }

    fun loadConversation(id: String): ConversationDetail? {
        val detailFile = File(historyDir, "$id.json")
        if (!detailFile.exists()) return null
        return try {
            val jsonString = detailFile.readText()
            JSONObject(jsonString).toConversationDetail()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteConversation(id: String) {
        val detailFile = File(historyDir, "$id.json")
        if (detailFile.exists()) {
            detailFile.delete()
        }
        val summaries = getSummaries().toMutableList()
        summaries.removeAll { it.id == id }
        saveSummaries(summaries)
    }

    private fun saveSummaries(summaries: List<ConversationSummary>) {
        try {
            val jsonArray = JSONArray()
            summaries.forEach { jsonArray.put(it.toJson()) }
            summaryFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // JSON Extension converters
    private fun ConversationSummary.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("timestamp", timestamp)
        put("selectedMode", selectedMode.name)
    }

    private fun JSONObject.toConversationSummary(): ConversationSummary {
        return ConversationSummary(
            id = getString("id"),
            title = getString("title"),
            timestamp = getLong("timestamp"),
            selectedMode = ChatMode.valueOf(getString("selectedMode")),
        )
    }

    private fun ConversationDetail.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("timestamp", timestamp)
        put("selectedMode", selectedMode.name)

        val messagesArray = JSONArray()
        messages.forEach { messagesArray.put(it.toJson()) }
        put("messages", messagesArray)

        val eventsObject = JSONObject()
        agentEvents.forEach { (msgId, eventList) ->
            val eventArray = JSONArray()
            eventList.forEach { eventArray.put(it.toJson()) }
            eventsObject.put(msgId, eventArray)
        }
        put("agentEvents", eventsObject)
    }

    private fun JSONObject.toConversationDetail(): ConversationDetail {
        val id = getString("id")
        val title = getString("title")
        val timestamp = getLong("timestamp")
        val selectedMode = ChatMode.valueOf(getString("selectedMode"))

        val messagesArray = getJSONArray("messages")
        val messagesList = mutableListOf<ChatMessage>()
        for (i in 0 until messagesArray.length()) {
            messagesList.add(messagesArray.getJSONObject(i).toChatMessage())
        }

        val eventsObject = getJSONObject("agentEvents")
        val eventsMap = mutableMapOf<String, List<AgentEvent>>()
        val keys = eventsObject.keys()
        while (keys.hasNext()) {
            val msgId = keys.next()
            val eventArray = eventsObject.getJSONArray(msgId)
            val eventList = mutableListOf<AgentEvent>()
            for (i in 0 until eventArray.length()) {
                eventList.add(eventArray.getJSONObject(i).toAgentEvent())
            }
            eventsMap[msgId] = eventList
        }

        return ConversationDetail(id, title, timestamp, selectedMode, messagesList, eventsMap)
    }

    private fun ChatMessage.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role.name)
        put("content", content)
        put("status", status.name)
        
        val toolCallsArray = JSONArray()
        toolCalls.forEach { toolCallsArray.put(it.toJson()) }
        put("toolCalls", toolCallsArray)

        val imageUrlsArray = JSONArray()
        imageUrls.forEach { imageUrlsArray.put(it) }
        put("imageUrls", imageUrlsArray)

        val attachmentsArray = JSONArray()
        attachments.forEach { attachmentsArray.put(it.toJson()) }
        put("attachments", attachmentsArray)
    }

    private fun JSONObject.toChatMessage(): ChatMessage {
        val id = getString("id")
        val role = MessageRole.valueOf(getString("role"))
        val content = getString("content")
        val status = MessageStatus.valueOf(getString("status"))
        
        val toolCallsArray = getJSONArray("toolCalls")
        val toolCallsList = mutableListOf<ChatToolCall>()
        for (i in 0 until toolCallsArray.length()) {
            toolCallsList.add(toolCallsArray.getJSONObject(i).toChatToolCall())
        }

        val imageUrlsArray = optJSONArray("imageUrls") ?: JSONArray()
        val imageUrlsList = mutableListOf<String>()
        for (i in 0 until imageUrlsArray.length()) {
            imageUrlsList.add(imageUrlsArray.getString(i))
        }

        val attachmentsArray = optJSONArray("attachments") ?: JSONArray()
        val attachmentsList = mutableListOf<ChatAttachment>()
        for (i in 0 until attachmentsArray.length()) {
            attachmentsList.add(attachmentsArray.getJSONObject(i).toChatAttachment())
        }

        return ChatMessage(id, role, content, toolCallsList, status, imageUrlsList, attachmentsList)
    }

    private fun ChatAttachment.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("mimeType", mimeType)
        put("isImage", isImage)
        put("base64Data", base64Data)
        put("textContent", textContent)
    }

    private fun JSONObject.toChatAttachment(): ChatAttachment {
        return ChatAttachment(
            name = getString("name"),
            mimeType = getString("mimeType"),
            isImage = getBoolean("isImage"),
            base64Data = if (isNull("base64Data")) null else optString("base64Data"),
            textContent = if (isNull("textContent")) null else optString("textContent"),
        )
    }

    private fun ChatToolCall.toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("id", id)
        put("name", name)
        put("arguments", arguments)
    }

    private fun JSONObject.toChatToolCall(): ChatToolCall {
        return ChatToolCall(
            index = getInt("index"),
            id = if (isNull("id")) null else optString("id"),
            name = if (isNull("name")) null else optString("name"),
            arguments = getString("arguments"),
        )
    }

    private fun AgentEvent.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("summary", summary)
        put("payloadJson", payloadJson)
        put("riskLevel", riskLevel?.name)
        put("parentEventId", parentEventId)
        put("createdAtMillis", createdAtMillis)
    }

    private fun JSONObject.toAgentEvent(): AgentEvent {
        return AgentEvent(
            id = getString("id"),
            type = AgentEventType.valueOf(getString("type")),
            summary = getString("summary"),
            payloadJson = if (isNull("payloadJson")) null else optString("payloadJson"),
            riskLevel = if (isNull("riskLevel")) null else RiskLevel.valueOf(getString("riskLevel")),
            parentEventId = if (isNull("parentEventId")) null else optString("parentEventId"),
            createdAtMillis = getLong("createdAtMillis"),
        )
    }
}
